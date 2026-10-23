# M6 退款售后接口文档（买家申请 / 商家审核 / 沙箱退款）

> 里程碑：M6 退款售后 ｜ 前置：M5 履约物流已收官
> 范围：订单项粒度退款申请、商家驳回/同意、支付宝沙箱退款、未发货库存回补、主订单聚合
> 硬约束：支付/退款走支付宝沙箱，不使用真实资金通道

## 一、端点总览

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/user/refund` | ROLE_USER | 申请退款（订单项整项退），返回退款单号 |
| GET | `/api/user/refund/order/{orderNo}` | ROLE_USER | 某主订单下的退款单列表（新在前） |
| GET | `/api/user/refund/{refundNo}` | ROLE_USER | 退款单详情 |
| GET | `/api/merchant/refund/list` | ROLE_MERCHANT | 本店退款单列表（`status` 可选） |
| GET | `/api/merchant/refund/{refundNo}` | ROLE_MERCHANT | 退款单详情 |
| POST | `/api/merchant/refund/{refundNo}/reject` | ROLE_MERCHANT | 驳回（body：`remark` 必填） |
| POST | `/api/merchant/refund/{refundNo}/approve` | ROLE_MERCHANT | 同意并调沙箱退款（body：`remark` 可选） |
| POST | `/api/merchant/refund/{refundNo}/retry` | ROLE_MERCHANT | 退款失败重试（同一退款单号重放） |

统一响应：`R{code,message,data}`，成功 `code=0`，业务异常 `code=1000`。
归属校验：`userId` / `shopId` 一律取自登录态；越权与不存在统一返回「订单不存在 / 退款单不存在」，不泄露存在性。

## 二、状态机

### 2.1 退款单 `refund_order.status`

```
0 待审核 ──商家驳回──> 3 已驳回（终态，可重新申请新单）
0 待审核 ──商家同意 + 沙箱成功──> 2 已退款（终态）
0 待审核 ──商家同意 + 沙箱失败──> 4 退款失败 ──重试成功──> 2
                                          └──重试失败──> 4（刷新错误码）
```

### 2.2 子订单 `sub_order.status`（M6 扩展 5/6/7）

```
1 待发货 / 2 待收货 / 7 部分退款 ──买家申请──> 5 退款中
5 ──驳回/失败（无其它待审核退款）──> 1 / 2 / 7（sub_status_before）
5 ──退款成功（全部订单项已退）──> 6 已退款（终态）
5 ──退款成功（部分订单项已退）──> 7 部分退款
7 ──商家发货（剩余商品）──> 2 待收货
7 ──买家确认收货（剩余商品）──> 3 已完成
```

守卫矩阵：

| 操作 | 允许的子订单状态 |
| --- | --- |
| 申请退款 | 1 / 2 / 7 |
| 商家发货 | 1 / 7 |
| 买家确认收货 | 2 / 7 |

### 2.3 主订单 `order.status`

全部子订单 ∈ {3 已完成, 4 已取消, 6 已退款} 时聚合：
- 全部为 6 → `1 → 4 已退款`
- 否则 → `1 → 2 已完成`

部分退款（存在 3/4/6 混合）走「2 已完成」。聚合内核 `OrderAggregationService`，退款成功/驳回/确认收货共用。

## 三、买家申请退款

`POST /api/user/refund`

```json
{
  "orderNo": "M1958...",
  "subOrderNo": "S1958...",
  "orderItemId": 19581234,
  "reason": "商品质量问题",
  "evidenceImages": ["/static/refund/e1.jpg"]
}
```

处理链：

1. 主订单存在且 `user_id = 登录态` → 否则「订单不存在」
2. 子订单属于该主订单；`status ∈ {1,2,7}` → 否则「当前状态不可申请退款」
3. 订单项属于该子订单 → 否则「订单不存在」
4. 同一订单项已有 `status ∈ {0,2}` 的退款单 → 「该商品已申请退款 / 该商品已退款」
5. 金额 = `order_item.price × order_item.quantity`（快照，不信任前端）
6. INSERT refund_order（`status=0`，`sub_status_before=当前子订单状态`）
7. 子订单条件更新 → 5 退款中（rows=0 →「当前状态不可申请退款」）

约束：`reason` 必填 ≤255；`evidenceImages` ≤3 张（URL 由 `/api/file/upload` 上传获得）。
响应：`data` = 退款单号（`RF` + 雪花）。

## 四、商家审核

### 4.1 驳回

`POST /api/merchant/refund/{refundNo}/reject`，body `{ "remark": "凭证不清晰" }`

- 仅 `status=0` 可审核；条件更新 `0→3`（rows=0 →「退款单状态已变更，请刷新」）
- 若该子订单仍有其它待审核退款 → 子订单保持 5；否则恢复 `sub_status_before`
- 触发主订单聚合

### 4.2 同意退款（同步调沙箱）

`POST /api/merchant/refund/{refundNo}/approve`，body `{ "remark": "同意退款" }`（可选）

支付宝参数（`alipay.trade.refund`）：

| 参数 | 取值 |
| --- | --- |
| `out_trade_no` | 主订单号 |
| `trade_no` | `payment_record.trade_no`（存在时优先） |
| `out_request_no` | `refund_order.refund_no`（幂等键） |
| `refund_amount` | `refund_order.refund_amount` |
| `refund_reason` | 买家退款原因 |

成功（`response.isSuccess()`）：
1. 条件更新 `0→2`，回填 `alipay_trade_no / refund_time / audit_time / audit_remark`
2. **未发货才回补库存**（`shipment` 不存在 → `product_sku.stock + quantity`）
3. 子订单按已退款金额判定：`Σ已退款 ≥ Σ订单项金额` → 6 已退款，否则 → 7 部分退款
4. 主订单聚合

失败：条件更新 `0→4`，落 `fail_code / fail_msg / audit_*`；恢复子订单（无其它待审核时）；接口返回「退款失败，可稍后重试」。
`AlipayApiException` 统一记 `fail_code=ALIPAY_EXCEPTION`，不向前端抛引擎异常。

### 4.3 失败重试

`POST /api/merchant/refund/{refundNo}/retry`（无 body）

- 仅 `status=4` 可重试，否则「当前状态不可重试」
- 使用同一 `refund_no` 重放（支付宝 `out_request_no` 天然幂等）
- 成功 → 2；失败 → 4 并刷新错误信息

## 五、退款单视图

```json
{
  "refundNo": "RF1958...",
  "orderNo": "M1958...",
  "subOrderNo": "S1958...",
  "orderItemId": 19581234,
  "spuId": 1,
  "skuId": 2,
  "productName": "原木餐椅",
  "shopId": 3,
  "quantity": 1,
  "refundAmount": 199.00,
  "reason": "商品质量问题",
  "evidenceImages": ["/static/refund/e1.jpg"],
  "status": 2,
  "auditRemark": "同意退款",
  "auditTime": "2026-10-21T17:00:00",
  "refundTime": "2026-10-21T17:00:00",
  "failMsg": null,
  "createTime": "2026-10-21T16:50:00"
}
```

## 六、错误文案表

| 场景 | 文案 |
| --- | --- |
| 主/子订单不存在或越权 | 订单不存在 |
| 退款单不存在或跨店铺 | 退款单不存在 |
| 子订单状态不允许申请 | 当前状态不可申请退款 |
| 同一订单项已有待审核 | 该商品已申请退款 |
| 同一订单项已退款 | 该商品已退款 |
| 原因缺失/过长 | 请填写退款原因 / 退款原因过长 |
| 凭证超限 | 凭证图片最多 3 张 |
| 非待审核审核 | 当前状态不可审核 |
| 并发审核已被处理 | 退款单状态已变更，请刷新 |
| 非失败状态重试 | 当前状态不可重试 |
| 沙箱退款失败 | 退款失败，可稍后重试 |
| 发货/收货守卫（M5 接口） | 当前状态不可发货 / 当前状态不可确认收货 |

## 七、已知边界

- 一次退款单只对应一个订单项；多商品需分别申请
- 同一子订单同时只允许一笔进行中的退款（5 退款中期间不可再申请其它订单项）
- 已发货订单退款不回补库存（货在买家手上），退货入库流程不做
- 不做平台介入/仲裁、不做退货物流、不做部分数量退款
- 退款成功后不可撤销；退款记录不物理删除
- 自动化测试全部 mock `AlipayClient`；手动冒烟才走真实沙箱
