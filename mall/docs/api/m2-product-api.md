# 多商家入驻 B2B2C 商城 —— M2 商品域接口文档

> 里程碑：M2（商品域：入驻/店铺/类目/商品/审核/买家浏览）
> 服务：`mall-gateway-web`（Spring Boot 3.3.4，单体多模块，端口 `8080`）
> 数据库设计：见 `docs/db/m2-schema.sql`（与本文件同源维护）
> M1 认证授权接口（注册/登录/刷新/登出/RBAC）：见 `docs/api/m1-auth-api.md`

---

## 一、通用约定

### 1.1 Base URL 与响应结构

与 M1 一致：所有接口以 `/api` 开头，统一返回 `{ "code": 0, "message": "success", "data": ... }`。
错误码取值约定同 M1（业务错误 HTTP 200 + body.code；鉴权失败真实 HTTP 401/403）。

### 1.2 认证与角色

```
Authorization: Bearer <accessToken>
```

| 角色 | 来源 | 可访问 |
| --- | --- | --- |
| 匿名买家 | 无令牌 | M2 新增放行端点（见 1.3） |
| USER（买家） | `/api/user/auth/login` | 同匿名（M2 无买家专属写操作） |
| MERCHANT（商家） | `/api/merchant/auth/login` | `/api/merchant/**`（另需 `auditStatus=1` 才绑 shopId，见 3.1） |
| ADMIN（管理员） | `/api/admin/auth/login` | `/api/admin/**`（过滤链 ROLE_ADMIN + 方法级权限码，见 2.2） |

### 1.3 M2 新增匿名放行端点

`GET /api/category/tree`、`GET /api/shop/{shopId}`、`GET /api/product/**`（search / hot-search / {spuId}）。

### 1.4 大 ID 序列化约定（重要）

雪花 ID（19 位 Long，如 `2112586603225444353`）超出 JS `Number.MAX_SAFE_INTEGER`，
后端统一将其序列化为 **JSON 字符串**；安全范围内的小 Long（如类目种子 ID `101`）保持数字。
前端请将 ID 视为 `string | number` 并原样回传，不要 `Number()` 转换。

### 1.5 分页结构（MyBatis-Plus Page）

```json
{ "records": [], "total": 25, "current": 1, "size": 10 }
```

---

## 二、状态机与权限码

### 2.1 商品状态机（product.status）

```
                 发布/编辑提交                    平台审核通过
   [商家] ──────────────────▶ 1 待审核 ──────────────────▶ 2 已上架 ─┐
                                │                         ▲    │     │
                 平台驳回(原因必填)│                商家重新上架│    │商家下架
                                ▼                         │    ▼     │
                            3 审核驳回 ────────────────────┘  4 商家下架│
                                                            │    │
                                              平台违规下架(原因必填, 仅 2/4)
                                                            ▼    ▼
                                                        5 平台违规下架
```

- 仅 `2 已上架` 对买家可见；`0 草稿` 当前不由接口产出（发布即待审核），预留下一步。
- 任何编辑保存后状态回到 `1 待审核`，`audit_remark` 清空。
- `5 平台违规下架` 商家不可自行重新上架（`/on` 拒绝）。
- 删除商品仅允许 `0/3/4` 状态。

### 2.2 权限码表（M2 新增，种子绑定 ROLE_SUPER）

| 权限码 | 类型 | 说明 | 保护端点 |
| --- | --- | --- | --- |
| `merchant:audit:list` | MENU | 入驻审核列表 | `GET /api/admin/merchant/audit/list` |
| `merchant:audit:handle` | BUTTON | 入驻审核处理 | `POST .../audit/{merchantId}/pass`、`/reject` |
| `merchant:manage:status` | BUTTON | 商家冻结/启用 | `POST /api/admin/merchant/{merchantId}/status` |
| `product:category:list` | MENU | 类目管理 | `GET /api/admin/category/tree` |
| `product:category:add` | BUTTON | 新增类目 | `POST /api/admin/category` |
| `product:category:edit` | BUTTON | 编辑类目/启停 | `PUT /api/admin/category/{id}`、`POST .../{id}/status` |
| `product:category:delete` | BUTTON | 删除类目 | `DELETE /api/admin/category/{id}` |
| `product:audit:list` | MENU | 商品审核列表 | `GET /api/admin/product/audit/list` |
| `product:audit:handle` | BUTTON | 商品审核处理 | `POST .../{spuId}/audit/pass`、`/reject` |
| `product:manage:list` | MENU | 商品管控列表 | `GET /api/admin/product/list` |
| `product:manage:off` | BUTTON | 违规下架 | `POST /api/admin/product/{spuId}/violation-off` |

---

## 三、文件上传

### POST /api/file/upload

登录三端通用（USER / MERCHANT / ADMIN）。multipart 表单上传图片。

- 限制：**≤ 5MB**；仅 `image/jpeg` / `image/png` / `image/webp`（contentType 白名单 + 文件头魔数双重校验）
- 存储：本地 `uploads/yyyyMM/UUID.扩展名`，经 `/static/**` 映射访问

**请求**：`multipart/form-data`，字段名 `file`

**响应**：

```json
{ "code": 0, "message": "success", "data": { "fileId": "2112...", "url": "/static/202610/uuid.jpg" } }
```

**curl**：

```bash
curl -X POST http://localhost:8080/api/file/upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/path/to/photo.jpg"
```

**错误文案**：`文件大小不能超过 5MB`、`不支持的文件类型`、`文件内容与类型不符`、`文件不能为空`

---

## 四、商家入驻（MERCHANT）

### 4.1 POST /api/merchant/apply —— 提交入驻申请

未提交过或驳回后重填均可提交；提交后 `audit_status=0` 等待平台审核。
**已通过审核的商家重复提交会被拒绝**（`入驻申请已通过，不能重复提交`）。

**请求体**：

```json
{
  "subjectName": "杭州木集文创有限公司",
  "licenseNo": "91330100MA27X12345",
  "licenseImg": "/static/202610/license.jpg",
  "shopName": "木集演示店",
  "shopLogo": "/static/202610/logo.jpg",
  "shopIntro": "专注温润木作好物"
}
```

| 字段 | 必填 | 约束 |
| --- | --- | --- |
| `subjectName` | 是 | ≤64 字符 |
| `licenseNo` | 是 | ≤64 字符 |
| `licenseImg` | 否 | ≤255 字符 |
| `shopName` | 是 | ≤64 字符 |
| `shopLogo` | 否 | ≤255 字符 |
| `shopIntro` | 否 | ≤500 字符 |

**响应**：`{ "code": 0, "data": null }`；副作用：MongoDB `merchant_audit_log` 写入 `SUBMIT` 留痕。

### 4.2 GET /api/merchant/apply/status —— 查询我的入驻状态

**响应**：

```json
{
  "code": 0,
  "data": {
    "auditStatus": 0, "auditRemark": null, "submitted": true,
    "subjectName": "...", "licenseNo": "...", "licenseImg": "...",
    "shopName": "...", "shopLogo": "...", "shopIntro": "..."
  }
}
```

- `auditStatus`：0 待审核 / 1 已通过 / 2 已驳回；`submitted`：是否已提交过申请（前端据此展示表单或状态页）。

---

## 五、店铺

### 5.1 商家端（MERCHANT，需审核通过）

未通过审核的商家调用返回 `入驻申请未通过审核不能操作`。

#### GET /api/merchant/shop/info —— 我的店铺信息

```json
{ "code": 0, "data": { "id": "2112...", "name": "木集演示店", "logo": "...", "intro": "...", "notice": "...", "bizStatus": 1 } }
```

#### PUT /api/merchant/shop/info —— 编辑店铺

```json
{ "name": "木集演示店", "logo": "...", "intro": "...", "notice": "今日全场包邮" }
```

`name` 必填 ≤64；`logo` ≤255；`intro`/`notice` ≤500。响应 `{ "code": 0, "data": null }`。

#### POST /api/merchant/shop/biz-status?status=1 —— 营业/休店

`status`：`1` 营业 / `0` 休店（其他值 → `营业状态只支持 1 营业 / 0 休店`）。

### 5.2 买家端（匿名）

#### GET /api/shop/{shopId} —— 店铺公开信息 + 已上架商品数

```json
{ "code": 0, "data": { "name": "木集演示店", "logo": "...", "intro": "...", "bizStatus": 1, "productCount": 3 } }
```

店铺不存在 → `店铺不存在`。

---

## 六、类目

### 6.1 买家端（匿名）

#### GET /api/category/tree —— 启用类目树

仅返回 `status=1` 节点（停用父类目的子节点不提升展示）。Redis 缓存 `mall:category:tree`。

```json
{
  "code": 0,
  "data": [
    { "id": 101, "parentId": 0, "name": "数码家电", "sort": 1, "status": 1,
      "children": [ { "id": 102, "parentId": 101, "name": "手机", "sort": 1, "status": 1, "children": [] } ] }
  ]
}
```

### 6.2 平台端（ADMIN + 权限码）

所有写操作后删除类目树缓存键，下一次买家读取重建。

#### GET /api/admin/category/tree —— 全量类目树（含停用）`product:category:list`

结构同上，含 `status=0` 节点；孤儿类目（父节点被删）提升到顶层便于修复。

#### POST /api/admin/category —— 新增类目 `product:category:add`

```json
{ "parentId": 101, "name": "智能手表", "sort": 4 }
```

`parentId` 必填（顶级为 0，父类目必须存在）；`name` 必填 ≤32；`sort` 必填。新类目默认启用。

#### PUT /api/admin/category/{id} —— 编辑类目 `product:category:edit`

```json
{ "name": "手机通讯", "sort": 1 }
```

仅 `name`/`sort` 可改（parentId 不允许换父）。

#### POST /api/admin/category/{id}/status?status=0 —— 停用/启用 `product:category:edit`

`status`：1 启用 / 0 停用（其他值 → `类目状态只支持 1 启用 / 0 停用`）。

#### DELETE /api/admin/category/{id} —— 删除类目 `product:category:delete`

删除保护：存在子类目 → `请先删除子类目`；存在商品引用 → `类目下存在商品，不能删除`。

---

## 七、商家商品（MERCHANT，需审核通过）

越权访问他店资源统一按 `商品不存在` 拒绝（不泄漏存在性）。

### 7.1 POST /api/merchant/product —— 发布商品

**请求体**：

```json
{
  "categoryId": 102,
  "name": "胡桃木手机支架",
  "subTitle": "整木切割 温润手感",
  "mainImg": "/static/202610/main.jpg",
  "imgList": ["/static/202610/a.jpg", "/static/202610/b.jpg"],
  "specs": [ { "specName": "颜色", "values": ["原木色", "深胡桃"] } ],
  "skus": [
    { "specValues": { "颜色": "原木色" }, "price": 39.00, "stock": 50, "skuImg": null },
    { "specValues": { "颜色": "深胡桃" }, "price": 45.00, "stock": 0, "skuImg": null }
  ],
  "detailHtml": "<p>整木切割，手工打磨。</p>"
}
```

| 校验 | 文案 |
| --- | --- |
| `categoryId` 必填且类目存在且启用 | `类目不能为空` / `类目不存在或已停用` |
| `name` 必填 ≤64 | `商品名称不能为空` / `商品名称不能超过 64 个字符` |
| `mainImg` 必填 | `主图不能为空` |
| `skus` ≥1 | `SKU 至少一条` |
| `price` ≥0 / `stock` ≥0 | `SKU 价格不能为负` / `SKU 库存不能为负` |
| 规格组合不可重复（键序无关） | `SKU 规格组合重复` |

**响应**：`{ "code": 0, "data": "2112608399827959809" }`（SPU ID，字符串）

**副作用**：SPU `status=1 待审核` + `price=SKU 最低价`；规格/SKU 同事务写入；MongoDB `product_detail` 写入富文本；删除详情缓存键。

### 7.2 PUT /api/merchant/product/{spuId} —— 编辑商品

请求体同发布。仅本店商品可编辑；保存后状态回 `1 待审核`、`auditRemark` 清空；规格/SKU 整批替换；Mongo 详情覆盖（保留 `_id`）；清缓存。

### 7.3 GET /api/merchant/product/list —— 本店商品分页

参数：`status`（可选）、`keyword`（可选，名称模糊）、`page`（默认 1）、`size`（默认 10）

```json
{
  "code": 0,
  "data": {
    "records": [
      { "spuId": "2112...", "name": "胡桃木手机支架", "mainImg": "...", "price": 39.00,
        "sales": 0, "status": 1, "auditRemark": null, "updateTime": "2026-10-21T01:47:41" }
    ],
    "total": 1, "current": 1, "size": 10
  }
}
```

### 7.4 GET /api/merchant/product/{spuId} —— 编辑回显详情

```json
{
  "code": 0,
  "data": {
    "spuId": "2112...", "categoryId": 102, "name": "...", "subTitle": "...",
    "mainImg": "...", "imgList": ["..."], "status": 2, "auditRemark": null,
    "specs": [ { "specName": "颜色", "values": ["原木色", "深胡桃"] } ],
    "skus": [ { "skuId": "2112...", "specValues": { "颜色": "原木色" }, "price": 39.00, "stock": 50, "skuImg": null } ],
    "detailHtml": "<p>...</p>"
  }
}
```

### 7.5 POST /api/merchant/product/{spuId}/off —— 商家下架（2→4）

仅 `已上架` 可操作，否则 `当前状态不允许下架`。

### 7.6 POST /api/merchant/product/{spuId}/on —— 重新上架（4→2）

仅 `商家下架` 可操作；`平台违规下架`（5）拒绝：`平台下架的商品不能重新上架`。

### 7.7 DELETE /api/merchant/product/{spuId} —— 删除商品

仅 `0 草稿 / 3 驳回 / 4 商家下架` 可删，否则 `当前状态不允许删除`。级联删除 SKU/规格/Mongo 详情并清缓存。

---

## 八、平台审核（ADMIN + 权限码）

### 8.1 入驻审核

#### GET /api/admin/merchant/audit/list —— 审核列表 `merchant:audit:list`

参数：`keyword`（可选，用户名/店铺名模糊）、`pageNum`（默认 1）、`pageSize`（默认 10）。仅含已提交申请的商家。

```json
{
  "code": 0,
  "data": {
    "records": [
      { "id": "2112...", "username": "m2ui001", "contactPhone": "138...",
        "auditStatus": 0, "auditRemark": null, "status": 1,
        "subjectName": "...", "licenseNo": "...", "licenseImg": "...",
        "shopName": "...", "shopLogo": "...", "shopIntro": "...",
        "createTime": "...", "updateTime": "..." }
    ],
    "total": 1, "current": 1, "size": 10
  }
}
```

#### POST /api/admin/merchant/audit/{merchantId}/pass —— 通过 `merchant:audit:handle`

`audit_status 0→1`，按申请草稿自动创建 `shop` 行；Mongo 留痕 `PASS`。重复审核拒绝：`该申请已处理`。

#### POST /api/admin/merchant/audit/{merchantId}/reject?remark=原因 —— 驳回 `merchant:audit:handle`

原因必填（`驳回原因不能为空`）；`0→2` + `audit_remark`；Mongo 留痕 `REJECT`。

#### POST /api/admin/merchant/{merchantId}/status?status=0 —— 冻结/启用 `merchant:manage:status`

`status`：1 启用 / 0 冻结（其他值 → `账号状态只支持 1 正常 / 0 冻结`）。冻结后商家令牌仍有效但写操作被拒（`账号已被冻结`）。

### 8.2 商品审核与管控

#### GET /api/admin/product/audit/list —— 待审核列表 `product:audit:list`

参数：`keyword`、`pageNum`、`pageSize`；按创建时间升序，含店铺名。

```json
{
  "code": 0,
  "data": {
    "records": [
      { "id": "2112...", "shopId": "2112...", "shopName": "木集演示店", "categoryId": 102,
        "name": "胡桃木手机支架", "subTitle": "...", "mainImg": "...", "price": 39.00,
        "sales": 0, "status": 1, "auditRemark": null, "createTime": "...", "updateTime": "..." }
    ],
    "total": 1, "current": 1, "size": 10
  }
}
```

#### POST /api/admin/product/{spuId}/audit/pass —— 审核通过 `product:audit:handle`

`1→2` 上架；清详情缓存；Mongo 留痕 `PASS`。非待审核拒绝：`当前状态不允许审核`。

#### POST /api/admin/product/{spuId}/audit/reject?remark=原因 —— 驳回 `product:audit:handle`

原因必填；`1→3` + `audit_remark`；Mongo 留痕 `REJECT`。

#### GET /api/admin/product/list —— 全平台商品管控 `product:manage:list`

参数：`shopId`、`status`、`keyword`、`pageNum`、`pageSize`；响应字段同审核列表。

#### POST /api/admin/product/{spuId}/violation-off?reason=原因 —— 违规下架 `product:manage:off`

原因必填（`违规下架原因不能为空`）；仅 `2 已上架 / 4 商家下架` → `5`，否则 `当前状态不允许违规下架`；`audit_remark` 记录原因；清缓存；Mongo 留痕 `VIOLATION_OFF`。

---

## 九、买家商品（匿名）

### 9.1 GET /api/product/search —— 搜索已上架商品

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `keyword` | 否 | 名称模糊（归一化空格）；非空时写入热搜榜 |
| `categoryId` | 否 | 含启用子孙类目（停用子树不纳入） |
| `shopId` | 否 | 精确店铺过滤 |
| `sort` | 否 | `default`(默认) / `sales` / `price_asc` / `price_desc` / `new`；其他值 → `不支持的排序方式` |
| `page` / `size` | 否 | 默认 1 / 10 |

仅返回 `status=2`。列表项结构同 7.3。结果不做缓存。

### 9.2 GET /api/product/hot-search —— 热搜词 Top10

```json
{ "code": 0, "data": ["手机支架", "耳机", "胡桃木"] }
```

### 9.3 GET /api/product/{spuId} —— 商品详情聚合

Redis 缓存 `mall:product:{spuId}`（TTL 30 分钟，写后删键重建）。非 `status=2` → `商品不存在或已下架`（code 1000）。

```json
{
  "code": 0,
  "data": {
    "spu": { "id": "2112...", "shopId": "2112...", "categoryId": 102, "name": "...",
             "subTitle": "...", "mainImg": "...", "imgList": ["..."],
             "price": 39.00, "sales": 0, "avgRating": 5.0, "commentCount": 0 },
    "skus": [ { "id": "2112...", "specValues": { "颜色": "原木色" }, "price": 39.00, "stock": 50, "skuImg": null } ],
    "specs": [ { "specName": "颜色", "values": ["原木色", "深胡桃"] } ],
    "detailHtml": "<p>...</p>",
    "shop": { "id": "2112...", "name": "木集演示店", "logo": "...", "intro": "...", "bizStatus": 1 }
  }
}
```

---

## 十、错误文案表（M2 新增）

| 场景 | message |
| --- | --- |
| 参数校验（@Valid） | 各字段注解文案（见上表） |
| 未过审商家操作店铺/商品 | `入驻申请未通过审核不能操作` |
| 重复提交入驻申请 | `入驻申请已通过，不能重复提交` |
| 店铺不存在（商家端） | `店铺不存在` |
| 营业状态非法 | `营业状态只支持 1 营业 / 0 休店` |
| 跨店访问商品 | `商品不存在` |
| 商家下架/上架状态非法 | `当前状态不允许下架` / `当前状态不允许上架` |
| 平台下架后商家上架 | `平台下架的商品不能重新上架` |
| 删除商品状态非法 | `当前状态不允许删除` |
| 重复审核（入驻/商品） | `该申请已处理` / `当前状态不允许审核` |
| 驳回/违规下架缺原因 | `驳回原因不能为空` / `违规下架原因不能为空` |
| 类目父节点不存在 | `父类目不存在` |
| 类目删除保护 | `请先删除子类目` / `类目下存在商品，不能删除` |
| 买家访问未上架商品 | `商品不存在或已下架` |
| 非法排序 | `不支持的排序方式` |
| 文件上传 | `文件不能为空` / `文件大小不能超过 5MB` / `不支持的文件类型` / `文件内容与类型不符` |

---

## 十一、关键链路 curl 速查

```bash
# 商家登录（验证码参见 M1 文档：GET /api/captcha → Redis 取码）
curl -X POST http://localhost:8080/api/merchant/auth/login -H "Content-Type: application/json" \
  -d '{"username":"m2ui001","password":"***","captchaKey":"K","captchaCode":"C"}'

# 发布商品
curl -X POST http://localhost:8080/api/merchant/product -H "Authorization: Bearer $MT" \
  -H "Content-Type: application/json" -d @product.json

# 平台审核通过
curl -X POST "http://localhost:8080/api/admin/product/$SPU/audit/pass" -H "Authorization: Bearer $AT"

# 违规下架
curl -X POST "http://localhost:8080/api/admin/product/$SPU/violation-off?reason=违规" -H "Authorization: Bearer $AT"

# 买家搜索 / 详情（匿名）
curl "http://localhost:8080/api/product/search?keyword=支架&sort=sales&page=1&size=10"
curl "http://localhost:8080/api/product/$SPU"
```
