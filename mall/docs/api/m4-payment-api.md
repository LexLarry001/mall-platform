# M4 支付域接口文档（支付宝沙箱）

> 里程碑：M4 支付宝沙箱支付与回调 ｜ 前置：M3 订单交易闭环
> 范围：支付下单（电脑网站支付）、异步回调、主动查询兜底；退款/优惠属后续里程碑
> 硬约束：沙箱环境，不走真实资金通道；密钥走环境变量注入，仓库不落真实密钥

## 一、端点总览

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/user/pay/{orderNo}` | ROLE_USER | 发起支付，返回支付宝电脑网站支付表单 HTML |
| POST | `/api/user/pay/{orderNo}/query` | ROLE_USER | 主动查询支付结果（回调不可达时的兜底推进） |
| POST | `/api/pay/notify` | 无（验签保证） | 支付宝异步回调，应答纯文本 success/failure |

统一响应：`/api/user/**` 走 `R{code,message,data}`；`/api/pay/notify` 按支付宝规范应答纯文本，**不走 R 结构**。

## 二、发起支付

`POST /api/user/pay/{orderNo}`

- **业务守卫**（顺序即优先级）：
  1. 订单不存在或非本人 → `订单不存在`（不泄露存在性）
  2. 订单非待支付（status≠0）→ `当前状态不可支付`
  3. 已超支付截止时间 → `订单已超时，请重新下单`
  4. 支付宝网关异常 → `支付服务暂不可用，请稍后重试`
- **幂等**：payment_record 与主订单 1:1，`uk_order_no` 冲突即复用（重试/并发安全）；支付宝对同一 out_trade_no 未支付前可重复唤起收银台。
- **响应**：`data` = 表单 HTML 字符串。前端注入隐藏 div 后**手动 `form.submit()`**（innerHTML 不执行内联 script）。

请求模型（服务端组装，前端无感）：

```
alipay.trade.page.pay
  out_trade_no = 主订单号
  total_amount = order.pay_amount（以库为准快照）
  subject      = 木集商城订单-{orderNo}
  product_code = FAST_INSTANT_TRADE_PAY
  notify_url   = ${MALL_ALIPAY_NOTIFY_URL}
  return_url   = ${MALL_ALIPAY_RETURN_URL}（默认 http://localhost:5173/pay/result）
```

## 三、异步回调

`POST /api/pay/notify`（application/x-www-form-urlencoded）

处理链：验签（RSA2，AlipaySignature.rsaCheckV1）→ 校验 app_id / out_trade_no 存在 / total_amount 与订单一致 → `trade_status ∈ (TRADE_SUCCESS, TRADE_FINISHED)` → markPaid → 应答 `success`。

| 场景 | 应答 | 订单推进 |
| --- | --- | --- |
| 验签通过 + TRADE_SUCCESS/FINISHED | success | 是（幂等） |
| 验签失败 | failure | 否 |
| app_id 不符 / 订单不存在 / 金额不符 | failure | 否 |
| 非成功状态（WAIT_BUYER_PAY 等） | success | 否（确认收到，阻断支付宝重发） |
| 重复通知（补发） | success | 幂等不二次推进 |

应答 `failure` 或非 200，支付宝按 25h 重发策略补发（8 次）。

## 四、主动查询兜底

`POST /api/user/pay/{orderNo}/query`

- 本地开发无公网 URL 收不到异步回调，**query 兜底是本地演示链路的关键一环**（PayResultView 挂载后自动调用）。
- 本人守卫（越权"订单不存在"）；订单已非待支付直接返回当前 status（不调网关）。
- `alipay.trade.query` → TRADE_SUCCESS/FINISHED 则 markPaid → 返回 `data=1`；WAIT_BUYER_PAY 等返回 `data=0`。
- 网关异常 → `支付服务暂不可用，请稍后重试`。

## 五、支付成功内核 markPaid（notify/query 共用，幂等）

```
主订单 0待支付 ──条件更新（行数 0 即已处理，直接返回）──> 1已支付
子订单 0待支付 ──> 1待发货
payment_record ──> status=1 + 回填 trade_no / notify_raw / pay_time
Redis ──> ZREM mall:order:timeout（清除超时关单锚点）
```

- 条件更新天然抗并发：notify 与 query 同时到达，后到者行数 0 幂等返回。
- 迟到支付（订单已超时取消后买家仍付款成功）：仅对 status=0 生效，已取消订单的成功回调/查询仅忽略，人工介入（退款属后续里程碑）。

## 六、支付时序

```
买家          前端           后端                支付宝沙箱
 │  去支付      │              │                    │
 │────────────>│ POST pay/{o} │                    │
 │             │─────────────>│ 守卫+落流水         │
 │             │              │──page.pay(表单)───>│
 │             │<──表单 HTML──│<───────────────────│
 │<────────────│              │                    │
 │──submit 表单（跳收银台）────────────────────────>│
 │──登录沙箱买家 + 付款─────────────────────────────>│
 │             │              │<──异步 notify(验签)─│  本地无公网不可达
 │<──return_url 跳回 /pay/result────────────────────│
 │             │ POST pay/{o}/query                │
 │             │─────────────>│──trade.query──────>│
 │             │              │<─TRADE_SUCCESS─────│
 │             │              │ markPaid(0→1 幂等)  │
 │<──支付成功───│<──data=1────│                    │
```

## 七、沙箱开通与密钥配置指南

1. 支付宝开放平台 → 控制台 → 沙箱：`https://open.alipay.com/develop/sandbox/app`
2. 取四要素：**沙箱 APPID**、**应用私钥**、**支付宝公钥**（系统默认密钥·公钥模式）、**沙箱买家账号**（含支付密码，默认 111111）。
3. 注入环境变量（仓库不落密钥，参考 `mall/alipay-sandbox.env` 头部用法，该文件已 gitignore）：

```powershell
Get-Content .\alipay-sandbox.env | ? { $_ -match '^[A-Z]' } | % { $kv = $_ -split '=', 2; [Environment]::SetEnvironmentVariable($kv[0], $kv[1], 'Process') }
```

| 环境变量 | 说明 |
| --- | --- |
| `MALL_ALIPAY_APP_ID` | 沙箱 APPID |
| `MALL_ALIPAY_PRIVATE_KEY` | 应用私钥（PKCS8 无密） |
| `MALL_ALIPAY_PUBLIC_KEY` | 支付宝公钥（验签用） |
| `MALL_ALIPAY_NOTIFY_URL` | 异步回调地址（需公网可达，见下节） |
| `MALL_ALIPAY_RETURN_URL` | 同步跳回地址（默认 `http://localhost:5173/pay/result`） |

4. 网关地址：沙箱 `https://openapi-sandbox.dl.alipaydev.com/gateway.do`（已在 application.yml 默认）；生产改 `https://openapi.alipay.com/gateway.do` 并替换四要素。
5. 空密钥不影响构建与全部自动化测试（AlipayClient 正常装配，仅真实网关调用失败）。
6. `MALL_ALIPAY_RETURN_URL` **不要设为空串**：空串会覆盖 yml 默认值导致支付后无回跳。`PaymentService` 已做兜底（空/空白时回落 `http://localhost:5173/pay/result`），单测 `createPayForm_blank_return_url_falls_back_to_default` 固化该行为。

## 八、内网穿透（联调真实异步回调）

本地 `localhost:8080` 支付宝不可达，如需验证真实 notify 链路：

1. 安装 cpolar / ngrok：`cpolar http 8080`
2. 得到公网地址（如 `https://xxxx.cpolar.cn`），设置 `MALL_ALIPAY_NOTIFY_URL=https://xxxx.cpolar.cn/api/pay/notify`
3. 重启后端，走完整付款链路，观察 payment_record.notify_raw 落报文 + 订单 0→1。

不穿透时的标准本地链路（已验证）：表单支付 → return_url 跳回 → query 兜底推进。

## 九、错误文案表

| 触发 | 文案 |
| --- | --- |
| 订单不存在/越权 | 订单不存在 |
| 订单非待支付 | 当前状态不可支付 |
| 超支付截止时间 | 订单已超时，请重新下单 |
| 支付宝网关异常（发起/查询） | 支付服务暂不可用，请稍后重试 |
| 前端表单 HTML 无 form 标签 | 支付表单异常，请稍后重试 |
| notify 验签/金额/APPID 失败 | failure（纯文本，支付宝将重发） |

## 十、数据设计

见 `docs/db/m4-schema.sql`（payment_record 单表，与主订单 1:1，无既有表变更，无新增 Redis Key）。
