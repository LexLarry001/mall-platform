# 多商家入驻 B2B2C 商城 —— M1 认证授权接口文档

> 里程碑：M1（工程骨架 + 认证授权）
> 服务：`mall-gateway-web`（Spring Boot 3.3.4，单体多模块，端口 `8080`）
> 在线接口文档：`http://localhost:8080/swagger-ui/index.html`（OpenAPI JSON：`/v3/api-docs`）
> 数据库设计：见 `docs/db/m1-schema.sql`（与本文件同源维护）

---

## 一、通用约定

### 1.1 Base URL

| 场景 | Base URL | 说明 |
| --- | --- | --- |
| 直连后端 | `http://localhost:8080` | 所有接口以 `/api` 开头 |
| 买家端前端 | `http://localhost:5173/api` | Vite proxy 转发到 8080 |
| 管理端前端 | `http://localhost:5174/api` | Vite proxy 转发到 8080 |

### 1.2 统一响应结构

所有 HTTP 接口（含错误）都返回下列结构：

```json
{ "code": 0, "message": "success", "data": null }
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | int | `0` 成功；其余为错误码（见 1.3） |
| `message` | string | 提示文案（成功固定为 `success`） |
| `data` | object / array / null | 业务数据，失败时为 `null` |

### 1.3 错误码表

| code | HTTP 状态码 | 含义 | 典型触发 |
| --- | --- | --- | --- |
| `0` | 200 | 成功 | — |
| `400` | 200 | 参数错误 | 必填字段缺失（`@Valid` 校验失败） |
| `401` | 200 或 401 | 未登录或登录已过期 | ①`POST /api/auth/refresh` 传过期/伪造刷新令牌（HTTP 200 + code 401）；②过滤器层令牌校验失败（**真实 HTTP 401** + code 401） |
| `403` | 403 | 无权限 | 跨端越权、缺少权限码（**真实 HTTP 403** + code 403） |
| `404` | 404 | 接口不存在 | 访问未映射路径（**真实 HTTP 404** + code 404） |
| `1000` | 200 | 业务错误 | 重复用户名、验证码错误、用户名或密码错误 |
| `500` | 200 | 系统异常 | 未预期异常 |

**取值约定（重要）**

- **业务错误**（`400` / `1000` / `500` 以及 refresh 失败）一律 **HTTP 200**，以 `body.code` 判断成败，前端 axios 拦截器即按此实现。
- **鉴权失败**（令牌缺失/过期/伪造、越权）返回 **真实 HTTP 401 / 403**，同时 body 仍是 `R` 结构。
- **路径不存在** 返回 **真实 HTTP 404**。

### 1.4 认证方式

除下列放行端点外，其余接口均需携带访问令牌：

```
Authorization: Bearer <accessToken>
```

放行端点：`GET /api/captcha`、`GET /api/ping`、`POST /api/{user|merchant}/auth/register`、`POST /api/{user|merchant|admin}/auth/login`、`POST /api/auth/refresh`、`/v3/api-docs/**`、`/swagger-ui/**`。
`POST /api/auth/logout` **不放行**（需凭令牌定位登录态）。

### 1.5 登录失败与注册失败的统一文案

| 场景 | message |
| --- | --- |
| 用户不存在 / 密码错误 | `用户名或密码错误`（两者同一文案，不泄漏账号是否存在） |
| 用户名重复 | `用户名已存在` |
| 验证码错误或过期 | `验证码错误或已过期` |
| 管理员自助注册 | `管理员账号不支持自助注册`（M1 未开放该端点，服务层保留防御） |

### 1.6 账号类型与令牌

| 账号类型 | 枚举值 | 前缀路径 | JWT `type` | 授权角色 |
| --- | --- | --- | --- | --- |
| 买家 | `USER` | `/api/user/**` | `USER` | `ROLE_USER` |
| 商家 | `MERCHANT` | `/api/merchant/**` | `MERCHANT` | `ROLE_MERCHANT` |
| 管理员 | `ADMIN` | `/api/admin/**` | `ADMIN` | `ROLE_ADMIN` |

JWT payload：`sub`=账号 ID、`type`=账号类型、`typ`=`access|refresh`、`shopId`（仅已审核商家非空，M1 统一为 `null`）、`iat`/`exp`/`jti`。
令牌有效期：`accessToken` 12 小时；`refreshToken` 7 天。

---

## 二、Redis 键设计（登录态）

| Key | 类型 | TTL | 说明 |
| --- | --- | --- | --- |
| `mall:captcha:{captchaKey}` | String | 5 min | 图形验证码答案，校验后即删（一次性） |
| `mall:token:{TYPE}:{accountId}` | String | 12 h | 该账号**当前有效**的 access token（单值覆盖 → 二次登录即顶号） |
| `mall:login:{token}` | String(JSON) | 12 h | 登录态快照 `LoginUser{accountId,type,shopId,permissions}` |
| `mall:refresh:{TYPE}:{accountId}` | String | 7 d | 该账号当前有效的 refresh token |
| `mall:perms:{adminId}` | Set | 30 min | 管理员权限码缓存（登录/刷新时装载） |

---

## 三、认证时序

```
① 取验证码        GET  /api/captcha                       → { key, imageBase64 }
② 注册（可选）    POST /api/user/auth/register             → code:0
③ 登录            POST /api/user/auth/login                → { accessToken, refreshToken, accountId, accountType, shopId }
④ 访问业务        GET  /api/...  Authorization: Bearer <accessToken>
⑤ 令牌过期        POST /api/auth/refresh  { refreshToken }  → 新的令牌对（旧 refresh 作废）
⑥ 退出登录        POST /api/auth/logout   Authorization: Bearer <accessToken>
⑦ 二次登录（顶号）再次 ③，旧 accessToken 立即 401
```

管理端在 ③ 之后额外调用 `GET /api/admin/auth/menus` 动态生成侧边栏菜单。

---

## 四、接口清单

### 4.1 `GET /api/captcha` —— 获取图形验证码

| 项 | 值 |
| --- | --- |
| 是否需要登录 | 否 |
| 请求参数 | 无 |

响应：

```json
{
  "code": 0,
  "message": "success",
  "data": { "key": "09dc8c03f2e14f0bb0c1f0d5d6be6a11", "imageBase64": "iVBORw0KGgoAAAANSUhEUgAA..." }
}
```

`imageBase64` 为 PNG 原始 base64（无 `data:` 前缀），前端拼成 `data:image/png;base64,<imageBase64>` 展示。

```bash
curl -s http://localhost:8080/api/captcha
```

---

### 4.2 `POST /api/user/auth/register` —— 买家注册

| 项 | 值 |
| --- | --- |
| 是否需要登录 | 否 |

请求：

```json
{ "username": "buyer01", "password": "buyer123", "contactPhone": null }
```

> 注意：M1 的注册**不校验验证码**，`captchaKey`/`captchaCode` 仅在登录时需要。
> `contactPhone` 仅商家注册使用，买家可省略。

响应：

```json
{ "code": 0, "message": "success", "data": null }
```

失败：用户名已存在 → `{"code":1000,"message":"用户名已存在","data":null}`（HTTP 200）；字段缺失 → `code 400`。

```bash
curl -s -X POST http://localhost:8080/api/user/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"buyer01","password":"buyer123"}'
```

---

### 4.3 `POST /api/merchant/auth/register` —— 商家入驻注册

请求：

```json
{ "username": "shop01", "password": "shop123456", "contactPhone": "13800000000" }
```

响应同 4.2。注册成功后 `merchant.audit_status=0`（待审核），可登录但登录态 `shopId=null`。

```bash
curl -s -X POST http://localhost:8080/api/merchant/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"shop01","password":"shop123456","contactPhone":"13800000000"}'
```

> 管理员不开放注册（无 `/api/admin/auth/register` 端点，访问该路径返回 HTTP 404 + code 404）。

---

### 4.4 `POST /api/user/auth/login` —— 买家登录

| 项 | 值 |
| --- | --- |
| 是否需要登录 | 否 |

请求：

```json
{
  "username": "buyer01",
  "password": "buyer123",
  "captchaKey": "<取自 4.1>",
  "captchaCode": "<图片中的 4 位数字>"
}
```

响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "accessToken": "eyJhbGciOiJIUzUxMiJ9...",
    "refreshToken": "eyJhbGciOiJIUzUxMiJ9...",
    "accountId": 2112515221900791810,
    "accountType": "USER",
    "shopId": null
  }
}
```

失败：`{"code":1000,"message":"验证码错误或已过期","data":null}` / `{"code":1000,"message":"用户名或密码错误","data":null}`。

```bash
curl -s -X POST http://localhost:8080/api/user/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"buyer01","password":"buyer123","captchaKey":"<KEY>","captchaCode":"<CODE>"}'
```

---

### 4.5 `POST /api/merchant/auth/login` —— 商家登录

请求/响应同 4.4，`accountType` 为 `MERCHANT`；待审核商家 `shopId` 为 `null`。

```bash
curl -s -X POST http://localhost:8080/api/merchant/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"shop01","password":"shop123456","captchaKey":"<KEY>","captchaCode":"<CODE>"}'
```

---

### 4.6 `POST /api/admin/auth/login` —— 管理员登录

请求同 4.4（`username=admin`，`password=admin123` 为内置种子账号）。

响应：`accountType` 为 `ADMIN`。登录成功后服务端会装载该管理员的权限码集合（写 `mall:perms:{adminId}`），供 `GET /api/admin/auth/menus` 裁剪菜单。

```bash
curl -s -X POST http://localhost:8080/api/admin/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123","captchaKey":"<KEY>","captchaCode":"<CODE>"}'
```

---

### 4.7 `POST /api/auth/refresh` —— 刷新令牌对

| 项 | 值 |
| --- | --- |
| 是否需要登录 | 否（需携带 refreshToken） |
| 权限 | 无 |

请求：

```json
{ "refreshToken": "<登录下发的 refreshToken>" }
```

响应：新的令牌对（结构与 4.4 相同）。刷新采用**轮换制**：旧 `refreshToken` 立即作废，须保存新的。

失败：过期 / 伪造 / 非 refresh 类型 → **HTTP 200** + `{"code":401,"message":"未登录或登录已过期","data":null}`。

```bash
curl -s -X POST http://localhost:8080/api/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"<REFRESH_TOKEN>"}'
```

---

### 4.8 `POST /api/auth/logout` —— 退出登录

| 项 | 值 |
| --- | --- |
| 是否需要登录 | **是**（`Authorization: Bearer <accessToken>`） |

请求体：无。

响应：`{"code":0,"message":"success","data":null}`；服务端清除 `mall:login:{token}`、`mall:token:{TYPE}:{id}`、`mall:refresh:{TYPE}:{id}`，**该令牌立即失效**。
未携带令牌或令牌无效 → **HTTP 401** + `{"code":401,...}`。

```bash
curl -s -X POST http://localhost:8080/api/auth/logout \
  -H "Authorization: Bearer <ACCESS_TOKEN>"
```

---

### 4.9 `GET /api/admin/auth/menus` —— 管理端动态菜单

| 项 | 值 |
| --- | --- |
| 是否需要登录 | 是（管理员令牌） |
| 权限 | `ROLE_ADMIN` |

响应（**扁平节点列表**，前端按 `parentId` 组树、按 `sort` 升序）：

```json
{
  "code": 0,
  "message": "success",
  "data": [
    { "id": 1, "parentId": 0, "name": "系统管理", "code": "system",          "type": "MENU", "path": "/system",       "sort": 10 },
    { "id": 2, "parentId": 1, "name": "用户管理", "code": "system:user:list", "type": "MENU", "path": "/system/user",  "sort": 11 },
    { "id": 3, "parentId": 1, "name": "角色管理", "code": "system:role:list", "type": "MENU", "path": "/system/role",  "sort": 12 }
  ]
}
```

仅返回**该管理员自身权限码内**的 `type=MENU` 节点（`BUTTON` 不入菜单）；权限取自登录态，不从请求参数取。
买家 / 商家令牌访问 → **HTTP 403** + `{"code":403,"message":"无权限","data":null}`。

```bash
curl -s http://localhost:8080/api/admin/auth/menus \
  -H "Authorization: Bearer <ADMIN_ACCESS_TOKEN>"
```

---

### 4.10 `GET /api/admin/system/user/list` —— 管理员账号列表（受权限码保护的示例端点）

| 项 | 值 |
| --- | --- |
| 是否需要登录 | 是（管理员令牌） |
| 权限 | `ROLE_ADMIN` **且**具权限码 `system:user:list` |

响应（不含密码哈希）：

```json
{
  "code": 0,
  "message": "success",
  "data": [ { "id": 1, "username": "admin", "status": 1, "createTime": "2026-10-20T18:20:00" } ]
}
```

缺少 `system:user:list` 权限码 → **HTTP 403** + `{"code":403,"message":"无权限","data":null}`。

```bash
curl -s http://localhost:8080/api/admin/system/user/list \
  -H "Authorization: Bearer <ADMIN_ACCESS_TOKEN>"
```

---

### 4.11 `GET /api/ping` —— 健康探测

| 项 | 值 |
| --- | --- |
| 是否需要登录 | 否 |

```json
{ "code": 0, "message": "success", "data": "pong" }
```

```bash
curl -s http://localhost:8080/api/ping
```

---

## 五、权限码表（M1 种子数据）

| 权限码 | 名称 | 类型 | 前端路由 | 排序 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `system` | 系统管理 | MENU | `/system` | 10 | 侧边栏分组 |
| `system:user:list` | 用户管理 | MENU | `/system/user` | 11 | 同时是 4.10 的方法级权限码 |
| `system:role:list` | 角色管理 | MENU | `/system/role` | 12 | M6 才会挂真实页面 |
| `system:user:add` | 新增用户 | BUTTON | — | 1 | 仅授权，不出现在菜单 |

角色 `ROLE_SUPER`（超级管理员）拥有上述全部权限，绑定内置账号 `admin`。

---

## 六、Windows PowerShell 执行注意（本地联调）

PowerShell 5.1 直接向 `curl` 传内联 JSON 会吞掉双引号，**必须改用临时文件**：

```powershell
# ① 取验证码，并从 Redis 读出答案（本地联调专用，正式流程由用户看图输入）
$json = curl.exe -s "http://localhost:8080/api/captcha" | ConvertFrom-Json
$captchaKey  = $json.data.key
$captchaCode = (& 'E:\Docker\resources\bin\docker.exe' exec mall-redis redis-cli GET "mall:captcha:$captchaKey").Trim()

# ② 登录
$body = @{ username='admin'; password='admin123'; captchaKey=$captchaKey; captchaCode=$captchaCode } | ConvertTo-Json -Compress
$f = "$env:TEMP\login.json"
[System.IO.File]::WriteAllText($f, $body, (New-Object System.Text.UTF8Encoding($false)))
$res = curl.exe -s -X POST "http://localhost:8080/api/admin/auth/login" -H "Content-Type: application/json" --data-binary "@$f" | ConvertFrom-Json
$token = $res.data.accessToken

# ③ 取菜单
curl.exe -s "http://localhost:8080/api/admin/auth/menus" -H "Authorization: Bearer $token"
```

---

## 七、维护说明

- 本文件描述的是**已实现**的接口；新增端点前先改代码再改文档，禁止超前设计。
- `docs/db/m1-schema.sql` 与 `mall/docker/mysql/init/01-auth-schema.sql`、`02-rbac-schema.sql` 三处 DDL 必须保持一致。
- 前端消费者：`mall-web/buyer-web`（4.1 / 4.2 / 4.4 / 4.8）、`mall-web/admin-web`（4.1 / 4.6 / 4.8 / 4.9）。

---

## 八、M1 已知边界（审查后确认的取舍）

以下为 M1 评审时明确"暂不实现"的行为，均不影响认证授权闭环，供后续里程碑排期：

1. **前端不做令牌自动续期**：登录时下发的 `refreshToken`（4.7）由前端保存但**不自动调用**。原因：访问令牌有效期 12h，且 4.7 契约完备，自动续期需要请求排队 + 重放，属过度设计；令牌失效时前端直接清登录态并跳登录页。后续如需"无感续期"，在 `api/http.ts` 的 401 分支加一次性静默刷新重放即可。
2. **注册接口不校验验证码**：`POST /api/{user|merchant}/auth/register` 不校验 `captchaKey/captchaCode`（4.2 注）。买家注册页仍保留验证码控件（便于后续启用），字段会被服务端忽略。若要启用，需在 `RegisterRequest` 补两个字段并调用 `CaptchaService.verify`。
3. **刷新失败的响应形态**：`POST /api/auth/refresh` 令牌失效时为 **HTTP 200 + body.code=401**（业务异常统一形态），与过滤器层的真实 HTTP 401 并存 —— 调用方**一律以 body.code 为准**。
4. **`mall:perms:{adminId}` 缓存**：读优先（命中即返回，TTL 30min），未命中回源查库并回填；因此角色/权限变更最多 30min 后生效。
5. **管理端菜单链接未注册路由**：`GET /api/admin/auth/menus` 返回的 `path`（如 `/system/user`）在 M1 前端尚未注册对应页面，点击会落到 `/403`；页面本体属 M6（系统管理）。
