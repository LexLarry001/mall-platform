# M10 评价体系与 AI 按评价推荐 · 接口文档

> 模块位置：`mall-order` → `com.mall.order.review`
> 前端入口：买家端商品详情「买家评价」、订单详情「去评价」、搜索框旁「AI 客服」；商家端「评价管理」；平台端「评价审核」。
> 建表脚本：`mall/docs/db/m10-schema.sql`（同 `mall/docker/mysql/init/11-review-schema.sql`）。

统一响应：

```json
{ "code": 0, "message": "success", "data": ... }
```

`Long` 主键在 JSON 中按字符串返回，前端统一按 `string` 处理。

---

## 1. 发表评价

`POST /api/user/review`（需登录，买家）

```json
{
  "orderItemId": 886000000000000002,
  "rating": 5,
  "content": "坐感舒适，做工扎实，物流也快",
  "images": ["/static/demo/sofa-multi/sofa-multi-1.jpg"],
  "anonymous": false
}
```

规则：

- 订单项必须属于当前用户，且所属子订单状态为 `3 已完成`；
- `order_item_id` 唯一键防重复评价，重复提交返回「该商品已评价」；
- `rating` 1~5；`content` 5~500 字；`images` 最多 6 张（先调 `POST /api/file/upload` 拿 `/static/**` URL）；
- 成功后事务内刷新商品评分（`avg_rating`、`comment_count`、`review_version + 1`）并清商品缓存；
- 事务提交后异步触发 AI 打标（`tags`、`sentiment`），失败不影响评价提交。

响应：`data` 为评价 ID（字符串）。

---

## 2. 我的评价

`GET /api/user/review/my?pageNum=1&pageSize=10`（需登录，买家）

响应 `{ records, total, current, size }`，`records` 字段：`id, spuId, productName, mainImg, rating, content, images, tags, merchantReply, createTime`。

---

## 3. 商品评价列表（公开）

`GET /api/product/{spuId}/reviews?pageNum=1&pageSize=5&rating=5&hasImage=true&tag=做工扎实`

| 参数 | 说明 |
| --- | --- |
| `rating` | 可选，1~5 |
| `hasImage` | 可选，只看有图评价 |
| `tag` | 可选，按 AI/词表标签过滤 |

响应：

```json
{
  "records": [
    {
      "id": "883000000000000001",
      "userName": "u****1",
      "rating": 5,
      "content": "…",
      "images": ["/static/…jpg"],
      "tags": ["整体满意", "做工扎实"],
      "merchantReply": "感谢您的反馈…",
      "replyTime": "2026-10-13T16:26:57",
      "createTime": "2026-10-13T16:26:57"
    }
  ],
  "total": 8,
  "ratingStat": { "5": 4, "4": 3, "3": 1, "2": 0, "1": 0 }
}
```

匿名评价 `userName` 脱敏为 `u****1`；仅 `status=1` 的评价对外展示。

---

## 4. 商品评价摘要（AI）

`GET /api/product/{spuId}/review-summary`

```json
{
  "spuId": 6001,
  "reviewCount": 8,
  "avgRating": 4.4,
  "recommendRate": 0.75,
  "summary": "多数买家认为坐感偏软、尺寸合适、做工扎实、性价比高…",
  "pros": ["坐感舒适，尺寸合适", "做工扎实，性价比高"],
  "cons": ["包装不够结实", "个别有轻微味道"],
  "tags": ["坐感舒适", "尺寸合适", "做工扎实", "性价比高"],
  "source": "ai",
  "updatedAt": "2026-10-22T23:49:19"
}
```

- `source=ai`：DeepSeek 根据代表评价生成；`source=stat`：评价数不足（默认 <3）或 AI 关闭时的统计降级版；
- 缓存 Key：`mall:review:summary:{spuId}:v{reviewVersion}`，默认 7 天；评价新增/隐藏/恢复后版本号 +1，自动失效。

---

## 5. 商家评价列表

`GET /api/merchant/review/list?pageNum=1&pageSize=10&rating=5&replied=false`（需登录，商家）

- 一律按登录态 `shopId` 过滤，只能看到本店评价；
- `replied`：`false` 待回复 / `true` 已回复；
- 字段：`id, spuId, productName, mainImg, rating, content, images, tags, userName, merchantReply, replyTime, createTime`。

---

## 6. 商家回复评价

`POST /api/merchant/review/{id}/reply`（需登录，商家）

```json
{ "reply": "感谢您的反馈，我们会继续改进做工细节" }
```

- 仅本店评价可回复；每条评价只能回复一次，重复回复返回业务错误；
- `reply` 2~300 字。

---

## 7. 平台评价列表

`GET /api/admin/review/list?pageNum=1&pageSize=10&shopId=&rating=&status=&keyword=`

权限：`review:audit:list`（内置 `ROLE_SUPER` 已绑定）

- `status`：`1` 展示中 / `0`（库里 2）已隐藏；
- `keyword` 匹配商品名或评价内容。

字段：`id, shopId, shopName, spuId, productName, mainImg, userName, rating, content, images, tags, status, hideReason, merchantReply, createTime`。

---

## 8. 隐藏 / 恢复评价

`POST /api/admin/review/{id}/hide`（权限 `review:audit:handle`）

```json
{ "reason": "含有广告或联系方式" }
```

`POST /api/admin/review/{id}/restore`（权限 `review:audit:handle`，无请求体）

- 隐藏后该评价不再对外展示，商品评分与评价数立即重算；
- 恢复后重新计入评分，并刷新摘要版本号。

---

## 9. AI 客服：按评价推荐商品

`POST /api/user/ai/recommend`（需登录，买家）

```json
{ "query": "小户型客厅想要不太软的沙发，预算3000以内", "limit": 5 }
```

| 参数 | 说明 |
| --- | --- |
| `query` | 必填，自然语言需求（≤200 字） |
| `budgetMax` | 可选，显式预算上限；未传时会尝试从 `query` 中解析 |
| `categoryId` | 可选，显式类目；未传时按家具词表解析（沙发/床/餐桌/书桌/灯等） |
| `limit` | 可选，1~10，默认 5 |

响应：

```json
{
  "source": "ai",
  "parsed": { "categoryId": 102, "budgetMax": 3000, "keywords": ["沙发"] },
  "summary": "预算 3000 元内，6001 云朵科技布三人沙发…",
  "items": [
    {
      "spuId": 6001,
      "name": "云朵科技布三人沙发",
      "mainImg": "/static/demo/sofa-multi/sofa-multi-1.jpg",
      "price": 2599.00,
      "avgRating": 4.4,
      "commentCount": 8,
      "score": 0.90,
      "reason": "2599 元在预算内…",
      "evidence": ["坐感舒适，尺寸放在家里刚刚好…"]
    }
  ]
}
```

实现要点：

1. 解析需求 → 类目 + 预算 + 关键词；
2. 关键词/类目召回候选商品（默认 12 个），每个商品取最多 3 条代表评价作为证据；
3. DeepSeek 按「证据只能引用给定评价原文」的约束重排并生成推荐理由；
4. 校验 `spuId` 必须在候选内、`evidence` 必须是评价原文子串，否则丢弃；
5. AI 关闭 / JSON 校验失败 / 超时 → `source=rule` 规则排序（评分 0.6 + 评价数 0.25 + 预算/关键词匹配），推荐理由由标签与标签统计生成；
6. 缓存 `mall:review:recommend:{md5}` 30 分钟；日配额 `mall:review:ai:daily:{yyyyMMdd}`（默认 500 次）、用户日配额（默认 50 次）。

---

## 配置项

`mall-gateway-web/src/main/resources/application.yml` → `mall.review`：

| 配置 | 环境变量 | 默认 | 说明 |
| --- | --- | --- | --- |
| `summary-min-count` | `MALL_REVIEW_SUMMARY_MIN_COUNT` | 3 | 低于该评价数直接走统计摘要 |
| `summary-max-reviews` | `MALL_REVIEW_SUMMARY_MAX_REVIEWS` | 30 | 送入模型的评价上限 |
| `summary-cache-ttl-minutes` | `MALL_REVIEW_SUMMARY_CACHE_MINUTES` | 10080 | 摘要缓存 7 天 |
| `recommend-candidates` | `MALL_REVIEW_RECOMMEND_CANDIDATES` | 12 | 推荐候选商品数 |
| `reviews-per-product` | `MALL_REVIEW_REVIEWS_PER_PRODUCT` | 3 | 每个候选商品取几条评价作为证据 |
| `recommend-cache-ttl-minutes` | `MALL_REVIEW_RECOMMEND_CACHE_MINUTES` | 30 | 推荐结果缓存 |
| `daily-call-limit` | `MALL_REVIEW_DAILY_CALL_LIMIT` | 500 | AI 日总调用上限 |
| `user-daily-limit` | `MALL_REVIEW_USER_DAILY_LIMIT` | 50 | 单用户日调用上限 |

> 推荐/摘要均依赖 `mall.ai.enabled=true` 与已配置的 `MALL_AI_API_KEY`（DeepSeek）。关闭 AI 时全部自动降级，不影响评价浏览、商家回复与平台审核。

---

## curl 示例

```bash
# 公开评价列表
curl 'http://localhost:8080/api/product/6001/reviews?pageNum=1&pageSize=5'

# AI 评价摘要
curl 'http://localhost:8080/api/product/6001/review-summary'

# 登录
TOKEN=$(curl -s -X POST http://localhost:8080/api/user/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"user01","password":"test123","captchaKey":"k","captchaCode":"1234"}' | jq -r .data.accessToken)

# AI 客服推荐
curl -X POST http://localhost:8080/api/user/ai/recommend \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"query":"小户型客厅想要不太软的沙发，预算3000以内","limit":3}'
```
