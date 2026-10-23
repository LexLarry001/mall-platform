# M7 物流地图接口文档（高德轨迹可视化）

> 里程碑：M7 物流地图可视化 ｜ 前置：M5 履约物流、M6 退款售后已收官
> 范围：发货地址落库、高德驾车路径规划、轨迹 `ROUTE` 节点、状态坐标插值、前端地图渲染
> 设计原则：后端只调**高德 Web 服务**（地理编码 + 驾车规划），前端只调**高德 JS API** 渲染；路线只算一次，失败自动降级手动物流，发货永不阻断

## 一、端点变化总览

M7 **不新增端点**，仅在 M5 物流接口上扩展字段与返回内容：

| 方法 | 路径 | 变化 |
| --- | --- | --- |
| POST | `/api/merchant/ship/{subOrderNo}` | 请求体新增 `fromAddress`（发货地址） |
| GET | `/api/merchant/ship/{subOrderNo}` | 响应新增 `fromAddress / toAddress / fromLng / fromLat / toLng / toLat` |
| GET | `/api/user/logistics/shipment/{subOrderNo}` | 同上 |
| GET | `/api/merchant/ship/trace/{shipmentNo}` | 轨迹数组可含 `nodeType=ROUTE` 节点（带 `polyline`） |
| GET | `/api/user/logistics/trace/{shipmentNo}` | 同上；`STATUS` 节点可带 `lng / lat` |
| POST | `/api/merchant/ship/progress/{shipmentNo}` | 行为增强：高德模式下自动计算并写入当前位置坐标 |

统一响应 `R{code,message,data}`；归属校验与错误文案沿用 M5（越权统一「订单不存在」）。

## 二、商家发货（扩展）

`POST /api/merchant/ship/{subOrderNo}`

```json
{
  "carrierCode": "YTO",
  "carrierName": "圆通",
  "trackingNo": "YTO123456789",
  "fromAddress": "上海市浦东新区世纪大道 100 号"
}
```

字段规则：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| fromAddress | `provider=amap` 时必填 | 商家发货地，高德地理编码源；≤255 字，自动 trim |
| trackingNo | 是 | ≤64 字 |
| carrierCode / carrierName | 是 | 承运商编码与名称 |

处理链（在 M5 基础上扩展）：

1. 校验子订单归属与状态（1 待发货 / 7 部分退款可发货）
2. 地址规范化：空串视为 null；`amap` 模式为空 → `请填写发货地址`；超长 → `发货地址过长`
3. 读取主订单 `full_address` 作为收货地址（快照，不随地址簿变更）
4. `provider.planRoute(fromAddress, toAddress)` → 高德地理编码两端 + 驾车路径规划
5. 路线可用 → `shipment.provider=amap`、写入 `from/to_address` 与四个坐标、`trace_count=2`
   路线不可用 → `shipment.provider=manual`、坐标为空、`trace_count=1`（仍保存地址原文）
6. INSERT shipment（幂等）→ 子订单 `1→2`（条件更新）
7. MongoDB 写轨迹：可用时先写 `ROUTE` 再写 `STATUS:SHIPPED`；降级时只写 `STATUS:SHIPPED`

响应：`data` = 发货单号字符串。

> 调用高德在本地事务内完成（3 秒超时），失败只记 WARN 并降级，不影响发货成功语义。

## 三、发货单返回结构（新增字段）

```json
{
  "shipmentNo": "SH1958...",
  "subOrderNo": "S1958...",
  "provider": "amap",
  "carrierCode": "YTO",
  "carrierName": "圆通",
  "trackingNo": "YTO123456789",
  "fromAddress": "上海市浦东新区世纪大道 100 号",
  "toAddress": "上海市徐汇区漕溪北路 200 号",
  "fromLng": 121.500000,
  "fromLat": 31.230000,
  "toLng": 121.436000,
  "toLat": 31.188000,
  "currentStatus": "IN_TRANSIT",
  "traceCount": 3
}
```

- `provider` 是**本次发货实际使用的模式**，可能与全局配置不同（高德失败时降级为 `manual`）
- `fromLng/fromLat/toLng/toLat` 为 `DECIMAL(10,6)`，降级时为 `null`

## 四、轨迹节点结构

`GET /api/user/logistics/trace/{shipmentNo}`（商家端路径同理）→ 数组，`ts` 时间倒序。

### 4.1 ROUTE 节点（M7 新增，发货时写一次）

```json
{
  "id": "66f1...",
  "shipmentNo": "SH1958...",
  "nodeType": "ROUTE",
  "status": "SHIPPED",
  "desc": "配送路线已规划：全程 10.0 公里，预计 20 分钟",
  "address": "上海市浦东新区世纪大道 100 号",
  "lng": 121.500000,
  "lat": 31.230000,
  "polyline": [[121.500000, 31.230000], [121.468000, 31.209000], [121.436000, 31.188000]],
  "ts": [2026, 10, 21, 22, 0, 0]
}
```

- `polyline`：高德 `route.paths[0].steps[].polyline` 拼接后的坐标数组，每项 `[lng, lat]`
- 折线只存 MongoDB（`logistics_trace` 集合），MySQL 不落折线
- `address` 为发货地；`lng/lat` 为起点坐标

### 4.2 STATUS 节点（含坐标）

```json
{
  "nodeType": "STATUS",
  "status": "IN_TRANSIT",
  "desc": "包裹到达上海浦东分拣中心",
  "address": null,
  "lng": 121.468000,
  "lat": 31.209000,
  "polyline": null
}
```

- 高德模式：`lng/lat` 为按状态比例沿折线插值出的当前位置
- 手动模式 / 降级：`lng/lat` 为 `null`，前端仅显示文字时间线

## 五、状态与位置插值比例

```
SHIPPED(已发货)          0%    起点
IN_TRANSIT(运输中)      50%
OUT_FOR_DELIVERY(派送中) 85%
DELIVERED(已签收)       100%   终点
```

- 插值按**折线累计长度**计算，不是按点序号平均
- 后端 `AmapLogisticsProvider.pointAt` 与前端 `utils/logisticsMap.ts#pointAtRatio` 使用同一算法与比例
- `POSITION` 节点仍为预留类型（未来接入实时位置上报）

## 六、降级策略

| 场景 | 行为 |
| --- | --- |
| 未配置 Web 服务 Key | `planRoute` 返回不可用，全链路按 manual 处理 |
| 地理编码无结果 / Key 服务不匹配（`INVALID_USER_KEY`） | 同上，WARN 日志记录 `info/infocode` |
| 网络超时（默认 3000ms） | 同上 |
| 路径规划无 `paths` 或折线为空 | 同上 |
| MongoDB 写入 ROUTE 失败 | 捕获后补写纯文本 `STATUS` 节点 |
| 前端无 Key / JS 加载失败 / 无 ROUTE 节点 | 不渲染地图，显示文字轨迹（`LogisticsMap.vue` 内置提示） |

降级只影响地图展示，**发货、推进、确认收货等业务操作不受影响**。

## 七、密钥与配置

### 7.1 后端（Web 服务 Key）

`mall/amap.env`（已 gitignore，禁止提交）：

```bash
MALL_AMAP_WEB_KEY=xxxxxxxxxxxxxxxx
```

`application.yml`：

```yaml
mall:
  logistics:
    provider: ${MALL_LOGISTICS_PROVIDER:manual}   # manual | amap
    amap:
      key: ${MALL_AMAP_WEB_KEY:}
      base-url: ${MALL_AMAP_BASE_URL:https://restapi.amap.com/v3}
      timeout-ms: ${MALL_AMAP_TIMEOUT_MS:3000}
```

启动前注入（PowerShell）：

```powershell
Get-Content .\amap.env | ? { $_ -match '^[A-Z]' } | % { $kv = $_ -split '=', 2; [Environment]::SetEnvironmentVariable($kv[0], $kv[1], 'Process') }
$env:MALL_LOGISTICS_PROVIDER='amap'
```

### 7.2 前端（Web 端 Key + 安全密钥）

`buyer-web/.env.local`、`admin-web/.env.local`（已 gitignore）：

```bash
VITE_AMAP_JS_KEY=xxxxxxxxxxxxxxxx
VITE_AMAP_SECURITY_CODE=xxxxxxxxxxxxxxxx
```

加载顺序（JS API 2.0 要求）：先 `window._AMapSecurityConfig = { securityJsCode }`，再加载
`https://webapi.amap.com/maps?v=2.0&key=...`。两个工程各自持有一份 `LogisticsMap.vue` 与
`utils/logisticsMap.ts`（当前唯一使用方，避免跨工程依赖）。

## 八、数据库变更

`mall/docs/db/m7-schema.sql`（存量库 ALTER）：

```sql
ALTER TABLE `shipment`
    ADD COLUMN `from_address` VARCHAR(255) NULL COMMENT '发货地址（M7 商家填写，高德地理编码源）' AFTER `tracking_no`,
    ADD COLUMN `to_address` VARCHAR(500) NULL COMMENT '收货地址（M7 主订单快照，高德地理编码源）' AFTER `from_address`;
```

坐标列 `from_lng/from_lat/to_lng/to_lat` 为 M5 预留字段，M7 启用。

## 九、错误文案新增

| 场景 | 文案 |
| --- | --- |
| amap 模式发货地址为空 | 请填写发货地址 |
| 发货地址超过 255 字 | 发货地址过长 |

其余错误文案与 M5 一致。

## 十、已知边界

- 路径为**示意路线**（驾车规划结果），不代表快递真实运输路径；状态仍由商家手动推进
- Web 端 Key 在浏览器可见，仅靠域名白名单降低滥用风险（毕设使用 localhost）
- 历史发货单无地址/坐标，地图不显示，文字时间线不受影响
- 高德免费配额有限：集成测试一律 mock `AmapClient`，仅手动冒烟消耗真实配额
