# 多商家入驻 B2B2C 商城 —— M3 交易域接口文档

> 里程碑：M3（交易域：收货地址 / 购物车 / 结算下单 / 订单中心 / 超时关单）
> 服务：`mall-gateway-web`（Spring Boot 3.3.4，单体多模块，端口 `8080`）
> 数据库设计：见 `docs/db/m3-schema.sql`（与本文件同源维护）
> M1 认证授权（注册/登录/刷新/登出/RBAC）：见 `docs/api/m1-auth-api.md`
> M2 商品域（入驻/店铺/类目/商品/审核/买家浏览）：见 `docs/api/m2-product-api.md`

---

## 一、通用约定

### 1.1 Base URL 与响应结构

与 M1/M2 一致：所有接口以 `/api` 开头，统一返回 `{ "code": 0, "message": "success", "data": ... }`。
业务错误 HTTP 200 + `body.code=1000`（BizException）；鉴权失败真实 HTTP 401/403。

### 1.2 认证与角色

```
Authorization: Bearer <accessToken>
```

M3 全部端点挂在 `/api/user/**`，要求 **ROLE_USER**（买家登录态）；
`userId` 一律取自登录态（`SecurityUtils.getAccountId()`），**禁止前端传参**，越权访问他人数据统一报"不存在"。

### 1.3 大 ID 序列化约定（同 M2）

雪花 ID（19 位 Long）序列化为 **JSON 字符串**；安全范围内的小 Long 保持数字。
前端请将 ID 视为 `string | number` 并原样回传，不要 `Number()` 转换。

### 1.4 时间字段格式

`LocalDateTime` 由 Jackson 序列化，默认输出 `[y,m,d,h,mi,s]` 数组；
前端按 `string | number[]` 兼容解析（buyer-web `formatDateTime` 工具已处理）。

### 1.5 金额单位

一律 `DECIMAL(10,2)`，单位元；运费固定 0（M3 决策，plan §三.2）。

---

## 二、状态机（M3 实现的流转）

### 2.1 主订单 status（`order`.status）

```
                买家取消 / 超时关单
   0 待支付 ────────────────────▶ 3 已取消
      │
      └──（1 已支付 / 2 已完成 由 M4 支付回调、M5 收货推进，M3 不触达）
```

守卫：
- 仅 `0` 可取消（买家主动/超时 Job 同语义）；其他状态报"当前状态不可取消"。
- 超时关单仅扫 `0`；已终态订单仅清 ZSet 锚点（幂等）。
- 主订单取消级联全部子订单。

### 2.2 子订单 status（`sub_order`.status)

```
                主订单取消级联 / 超时关单
   0 待支付 ────────────────────▶ 4 已取消
      │
      └──（1 待发货 / 2 待收货 / 3 已完成 由 M4/M5 推进，M3 不触达）
```

### 2.3 库存与金额不变量

- **超卖防护**：下单时 `UPDATE product_sku SET stock=stock-? WHERE id=? AND stock>=?`，行数 0 即超卖，整单回滚。
- **金额校验**：`sum(sub_order.amount) == order.total_amount`，不等抛异常整单回滚（兜底，正常路径不会触发）。
- **库存回补**：取消/超时关单时，仅对 `0→关闭` 生效的订单逐项 `stock=stock+?`（幂等，条件更新行数 0 即已并发处理）。

---

## 三、收货地址 `/api/user/address`

### 3.1 地址列表

```
GET /api/user/address
```

响应 `data: AddressView[]`（按创建时间正序，默认地址不置顶由前端排序）：

```json
[
  {
    "id": "1011",
    "receiver": "张三",
    "phone": "13800000001",
    "province": "浙江省",
    "city": "杭州市",
    "district": "西湖区",
    "detail": "文三路 100 号 1 幢 301 室",
    "isDefault": 1
  }
]
```

### 3.2 新增地址

```
POST /api/user/address
Content-Type: application/json

{
  "receiver": "张三",
  "phone": "13800000001",
  "province": "浙江省",
  "city": "杭州市",
  "district": "西湖区",
  "detail": "文三路 100 号 1 幢 301 室",
  "isDefault": true
}
```

- `isDefault=true` 时服务端**同事务先清旧默认再写新默认**（同一买家仅一条 `is_default=1`）。
- 缺省/false 按非默认处理。

### 3.3 编辑地址

```
PUT /api/user/address/{id}
```

请求体同 3.2。越权/不存在统一 `地址不存在`。

### 3.4 删除地址

```
DELETE /api/user/address/{id}
```

- 删除默认地址后**不自动提拔新默认**（买家下次结算需手动选，plan Task 1 决策）。
- 越权/不存在统一 `地址不存在`。

### 3.5 设为默认

```
POST /api/user/address/{id}/default
```

同事务清旧默认 → 写新默认。

### 3.6 校验地址能否被高德识别（只读，不落库）

```
GET /api/user/address/geocode?address=浙江省杭州市西湖区文三路90号
Authorization: Bearer <accessToken>
```

响应 `data`：

```json
{
  "recognized": true,
  "lng": 120.146278,
  "lat": 30.278893,
  "formattedAddress": "浙江省杭州市西湖区文三路90号",
  "level": "门牌",
  "message": "已识别"
}
```

| 字段 | 说明 |
|---|---|
| `recognized` | 是否解析成功；未配置 Key / 地址不完整 / 无结果均为 `false` |
| `level` | 高德匹配粒度（省 / 市 / 区县 / 开发区 / 乡镇 / 村庄 / 热点商圈 / 道路 / 门牌） |
| `message` | 成功固定"已识别"，失败给出补全建议 |

用于买家端新增/编辑地址弹窗的"校验高德识别"按钮；物流可视化的地理编码也复用同一能力（`AmapClient.geocodeDetail`）。

---

## 四、购物车 `/api/user/cart`

存储：Redis Hash `mall:cart:{userId}`，field=skuId，value=`{"quantity":2,"checked":true}`。
失效项（下架/删除/库存 0）仅打标 `invalid=true` 不自动删除，**不参与结算**。

### 4.1 加购

```
POST /api/user/cart
Content-Type: application/json

{ "skuId": "2112608399827959809", "quantity": 2 }
```

- 同款（同 skuId）重复加购**数量累加合并**，勾选状态保留，新条目默认勾选。
- SKU 不存在或未上架（status≠2）报 `商品不存在或已下架`。

### 4.2 购物车列表

```
GET /api/user/cart
```

响应 `data: CartItemView[]`（实时联查 SKU/SPU/Shop，顺序=Redis Hash field 序）：

```json
[
  {
    "skuId": "2112608399827959809",
    "spuId": "2112608399827959808",
    "shopId": "2112600087875031041",
    "shopName": "木器小筑",
    "productName": "手工胡桃木餐盘",
    "skuImg": "https://.../plate.jpg",
    "specJson": "{\"颜色\":\"原木色\",\"尺寸\":\"中号\"}",
    "price": 128.00,
    "stock": 50,
    "quantity": 2,
    "checked": true,
    "invalid": false
  }
]
```

- `invalid=true` 三种情形：SKU 被删除 / SPU 未上架 / 库存为 0。
- Redis 故障统一 `购物车服务不可用，请稍后重试`（不穿透到空列表）。
- 坏 JSON 条目读取时自愈删除（防污染）。

### 4.3 修改条目

```
PUT /api/user/cart/{skuId}
Content-Type: application/json

{ "quantity": 3, "checked": false }
```

- `quantity` / `checked` 至少给一个，缺省字段保持原值。
- `quantity < 1` 报参数校验错误；条目不存在静默成功（幂等）。

### 4.4 移除条目

```
DELETE /api/user/cart/{skuId}
```

条目不存在静默成功（幂等）。

---

## 五、结算与下单 `/api/user/order`

### 5.1 结算页数据

```
GET /api/user/order/settle
```

响应 `data: SettleView`：

```json
{
  "defaultAddress": { "id": "1011", "receiver": "张三", "...": "..." },
  "items": [ "勾选且未失效的 CartItemView" ],
  "totalAmount": 256.00
}
```

- `items` = 购物车勾选且未失效条目（实时校验价格/库存，以库为准）。
- 勾选项为空或全部失效 → `没有可结算的商品`。
- 某条目 `stock < quantity` → `商品【{productName}】库存不足`。
- `defaultAddress` 为 null 时前端引导新增地址。

### 5.2 提交订单

```
POST /api/user/order
Content-Type: application/json

{ "addressId": "1011" }
```

响应 `data: "M21126..."`（主订单号，M+雪花字符串）。

**服务端事务流程**（plan Task 3）：

```
1. 防重锁 mall:order:submit:{userId} SET NX EX 5s，未抢到 → "请勿重复提交"
2. 地址归属校验：越权/不存在 → "地址不存在"
3. 勾选项为空 → "没有可结算的商品"
4. 库存条件扣减（stock>=?）行数 0 即超卖 → "商品【名】库存不足"，整单回滚
5. 写主订单（地址快照 receiver/phone/full_address，expire_time=now+30min）
6. 按 shop 拆子订单（一店一单，组间顺序=勾选顺序），订单项写快照
7. 金额校验 sum(sub)==main 不等 → 抛异常整单回滚（兜底）
8. 清购物车已勾选项 + ZSet mall:order:timeout 写超时锚点（Redis 不参与回滚）
9. 返回主订单号
```

### 5.3 订单列表

```
GET /api/user/order/list?status=0
```

- `status` 可选；缺省返回全部。
- 按 ID 倒序（创建时间倒序）。
- `items` 为跨子订单聚合的订单项（列表页缩略展示用，不区分店铺）。

响应 `data: OrderSummaryView[]`：

```json
[
  {
    "orderNo": "M21126...",
    "status": 0,
    "totalAmount": 256.00,
    "payAmount": 256.00,
    "createTime": "2026-10-21T10:30:05",
    "expireTime": "2026-10-21T11:00:05",
    "items": [ { "spuId": "...", "skuId": "...", "productName": "...", "mainImg": "...",
                 "specJson": "...", "price": 128.00, "quantity": 2 } ]
  }
]
```

### 5.4 订单详情

```
GET /api/user/order/{orderNo}
```

- 仅本人可见；越权/不存在统一 `订单不存在`。

响应 `data: OrderDetailView`：

```json
{
  "orderNo": "M21126...",
  "status": 0,
  "totalAmount": 256.00,
  "payAmount": 256.00,
  "receiver": "张三",
  "phone": "13800000001",
  "fullAddress": "浙江省杭州市西湖区文三路 100 号 1 幢 301 室",
  "createTime": "2026-10-21T10:30:05",
  "expireTime": "2026-10-21T11:00:05",
  "subOrders": [
    {
      "subOrderNo": "S21126...",
      "shopId": "2112600087875031041",
      "shopName": "木器小筑",
      "amount": 256.00,
      "status": 0,
      "items": [ "OrderItemView" ]
    }
  ]
}
```

### 5.5 取消订单

```
POST /api/user/order/{orderNo}/cancel
```

- 仅本人 + 仅 `status=0`（待支付）。
- 级联：全部子订单 →4 + 逐项回补库存 + ZSet 移除超时锚点。
- 越权/不存在 → `订单不存在`；非待支付 → `当前状态不可取消`（条件更新幂等，并发已处理同报错）。

### 5.6 超时关单（系统行为，无对外端点）

`OrderTimeoutJob` `@Scheduled(fixedDelay=60000)` 每分钟执行：

```
ZRANGEBYSCORE mall:order:timeout 0 now
  → 对每个到期 orderNo 执行 cancelTimeout（与 5.5 同语义，幂等仅 0→3 生效）
  → 已终态订单仅清 ZSet 残留锚点
  → 单点失败记日志不阻塞后续
```

超时时间：`application.yml` `mall.order.pay-timeout-minutes: 30`。

---

## 六、错误文案表（BizException code=1000）

| 触发场景 | 文案 |
| --- | --- |
| 结算/下单时勾选项为空或全部失效 | `没有可结算的商品` |
| 结算/下单时库存不足 | `商品【{productName}】库存不足` |
| 下单地址越权/不存在 | `地址不存在` |
| 下单防重锁未抢到 | `请勿重复提交` |
| 地址越权/不存在（编辑/删除/设默认） | `地址不存在` |
| 订单越权/不存在（详情/取消） | `订单不存在` |
| 取消非待支付订单 | `当前状态不可取消` |
| 加购 SKU 不存在或未上架 | `商品不存在或已下架` |
| Redis 故障（购物车读写） | `购物车服务不可用，请稍后重试` |
| 金额校验兜底（正常不触发） | `订单金额校验失败` |

---

## 七、前端路由对照（buyer-web）

| 路由 | 视图 | 关联接口 |
| --- | --- | --- |
| `/cart` | CartView | GET/PUT/DELETE `/api/user/cart` |
| `/checkout` | CheckoutView | GET `/api/user/order/settle` + POST `/api/user/order` + GET `/api/user/address` |
| `/orders` | OrderListView | GET `/api/user/order/list` + POST `/api/user/order/{orderNo}/cancel` |
| `/orders/:id` | OrderDetailView | GET `/api/user/order/{orderNo}` + POST `.../cancel` |
| `/address` | AddressView | GET/POST/PUT/DELETE `/api/user/address` + POST `.../default` |
| `/product/:id` 加购按钮 | ProductDetailView | POST `/api/user/cart` |

订单"去支付"按钮前端 disabled 占位 `M4 开放`（plan §三.8 决策）。

---

## 八、M3 范围外（留给后续里程碑）

- 支付宝沙箱支付与回调（M4）、退款（M4）
- 商家发货 / 物流轨迹 / 确认收货（M5，物流域通过 LogisticsProvider 接口预留扩展，后续可无缝接入高德地图 API）
- 评价（M5）
- 商家端/平台端订单页面（M5）
- 站内消息
