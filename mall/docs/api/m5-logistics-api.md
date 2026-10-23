# M5 履约与物流接口文档（手动发货 + 轨迹 + 确认收货）

> 里程碑：M5 履约与物流 ｜ 前置：M4 支付宝沙箱支付已收官
> 范围：商家发货、手动推进物流状态、买家查看轨迹与确认收货
> 硬约束：物流域可插拔（`LogisticsProvider`），本期仅 `ManualLogisticsProvider`；为高德可视化预留字段

## 一、端点总览

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/merchant/order/list` | ROLE_MERCHANT | 本店子订单列表（含买家快照、订单项、发货单状态） |
| POST | `/api/merchant/ship/{subOrderNo}` | ROLE_MERCHANT | 发货，返回发货单号 |
| POST | `/api/merchant/ship/progress/{shipmentNo}` | ROLE_MERCHANT | 推进物流状态，返回下一状态 |
| GET | `/api/merchant/ship/{subOrderNo}` | ROLE_MERCHANT | 查本店发货单 |
| GET | `/api/merchant/ship/trace/{shipmentNo}` | ROLE_MERCHANT | 查本店物流轨迹（时间倒序） |
| GET | `/api/user/logistics/shipment/{subOrderNo}` | ROLE_USER | 查本人订单发货单 |
| GET | `/api/user/logistics/trace/{shipmentNo}` | ROLE_USER | 查本人订单物流轨迹（时间倒序） |
| POST | `/api/user/logistics/confirm` | ROLE_USER | 确认收货（query：orderNo、subOrderNo） |

统一响应：`R{code,message,data}`，成功 `code=0`，业务异常 `code=1000`。

**归属校验**：`shopId` / `userId` 一律取自登录态，禁止前端传参；越权与不存在统一返回「订单不存在」，不泄露存在性。

## 二、商家订单列表

`GET /api/merchant/order/list?status=2`

- `status` 可选：0 待支付 / 1 待发货 / 2 待收货 / 3 已完成 / 4 已取消
- 仅返回 `sub_order.shop_id = 登录态 shopId` 的子订单，按创建时间倒序

响应 `data` 数组元素：

```json
{
  "subOrderNo": "S1958...",
  "orderNo": "M1958...",
  "status": 1,
  "amount": 199.00,
  "createTime": "2026-10-21T16:00:00",
  "receiver": "张三",
  "phone": "13800000000",
  "fullAddress": "上海市浦东新区世纪大道 1 号",
  "items": [
    { "spuId": 1, "skuId": 2, "productName": "原木餐椅", "mainImg": "/static/x.jpg",
      "specJson": "{\"颜色\":\"原木色\"}", "price": 199.00, "quantity": 1 }
  ],
  "shipmentNo": null,
  "carrierName": null,
  "trackingNo": null,
  "logisticsStatus": null,
  "traceCount": null
}
```

发货后 `shipmentNo / carrierName / trackingNo / logisticsStatus / traceCount` 有值。

## 三、商家发货

`POST /api/merchant/ship/{subOrderNo}`

```json
{ "carrierCode": "YTO", "carrierName": "圆通", "trackingNo": "YTO123456789" }
```

处理链：

1. 按 `subOrderNo` 查子订单；不存在或 `shop_id ≠ 登录态 shopId` → `订单不存在`
2. `sub_order.status ≠ 1` → `当前状态不可发货`
3. 生成 `shipment_no = SH + 雪花`，INSERT shipment（`uk_sub_order` 冲突 → `该子订单已发货`）
4. 子订单条件更新 `status 1→2`（rows=0 → `该子订单已发货`）
5. 追加 MongoDB 轨迹节点 `STATUS:SHIPPED`，`desc = 包裹已交给{承运商}，运单号 {trackingNo}`

响应：`data` = 发货单号字符串。

**幂等**：`shipment.uk_sub_order` 唯一键 + 子订单条件更新双保险，重复发货被拒。

## 四、商家推进物流

`POST /api/merchant/ship/progress/{shipmentNo}`

```json
{ "desc": "包裹已到达上海浦东分拣中心" }
```

- 状态机：`SHIPPED → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED`
- 终态 `DELIVERED` 再推进 → `物流已签收，无法推进`
- 越权（shipment 对应子订单不属于本店）→ `订单不存在`
- 每次推进：更新 `shipment.current_status` + `trace_count = trace_count + 1`，并追加一条轨迹节点

响应：`data` = 下一状态常量（如 `IN_TRANSIT`）。

## 五、买家查询与确认收货

### 5.1 查发货单

`GET /api/user/logistics/shipment/{subOrderNo}` → `shipment` 实体字段（shipmentNo/carrierName/trackingNo/currentStatus/traceCount 等）。

### 5.2 查轨迹

`GET /api/user/logistics/trace/{shipmentNo}` → 数组，时间倒序（最新在前）：

```json
[
  { "id": "...", "shipmentNo": "SH1958...", "nodeType": "STATUS", "status": "IN_TRANSIT",
    "desc": "包裹已到达上海浦东分拣中心", "address": null, "lng": null, "lat": null,
    "polyline": null, "ts": [2026,10,21,16,10,0] }
]
```

### 5.3 确认收货

`POST /api/user/logistics/confirm?orderNo=M1958...&subOrderNo=S1958...`

- 子订单 → 主订单 → 买家四级归属校验，不匹配 → `订单不存在`
- `sub_order.status ≠ 2` → `当前状态不可确认收货`
- 子订单条件更新 `2→3`，随后聚合检查：全部子订单 `status ∈ {3,4}` → 主订单 `1→2 已完成`

## 六、状态机

```
子订单：1 待发货 ──商家发货──> 2 待收货 ──买家确认收货──> 3 已完成
主订单：1 已支付 ──全部子订单终态──> 2 已完成
shipment：SHIPPED → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED
```

说明：

- 物流中间状态**不写入**子订单 status，保证交易状态机简单稳定
- 买家确认收货**不会**自动把 shipment 推到 DELIVERED，物流终态由商家推进
- 发货不改变主订单状态

## 七、错误文案表

| 场景 | 文案 |
| --- | --- |
| 子订单/发货单不存在、跨店或跨买家操作 | 订单不存在 |
| 发货时子订单状态 ≠ 1 | 当前状态不可发货 |
| 重复发货（唯一键冲突/条件更新失败） | 该子订单已发货 |
| 推进时 shipment 已 DELIVERED | 物流已签收，无法推进 |
| 确认收货时子订单状态 ≠ 2 | 当前状态不可确认收货 |

## 八、LogisticsProvider 扩展说明（高德接入预留）

接口 `com.mall.order.logistics.LogisticsProvider`：

```java
void createTrace(String shipmentNo, String desc);
void pushStatus(String shipmentNo, String nextStatus, String desc);
List<LogisticsTraceDoc> queryTrace(String shipmentNo);
```

职责边界：Provider 只负责**轨迹数据写入与查询**（本期 MongoDB），MySQL `shipment` 的状态更新由 Service 层完成。

接高德时新增 `AmapLogisticsProvider` 并切换配置即可，业务代码零改动：

```yaml
mall:
  logistics:
    provider: amap   # 当前 manual
```

高德接入步骤（占位）：

1. 商家发货时对收货地址做地理编码，写入 `shipment.to_lng/to_lat` 与轨迹 `toLng/toLat`
2. 调用高德路径规划，把折线写入轨迹 `polyline`，前端地图渲染
3. 位置上报端写入 `POSITION` 类型节点（`lng/lat/address`），前端轮询或 SSE 展示实时位置
4. `pushStatus` 仍可作为人工兜底状态推进
