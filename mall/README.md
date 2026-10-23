# mall · 多商家家具电商平台后端

> Spring Boot 3.3.4 · JDK 17 · Maven 多模块 · MySQL 8 · Redis 7 · MongoDB 6
>
> 多商家入驻 B2B2C 电商后端：认证与 RBAC、商品与审核、跨店拆单、支付宝沙箱支付、物流轨迹、退款售后、评价体系，以及 AI 网关（DeepSeek / Embedding / 向量检索）与 RAG 智能导购。

## 模块说明

| 模块 | 职责 |
|---|---|
| `mall-common` | 统一响应 `R`、错误码、业务异常、全局异常处理、雪花 ID JSON 序列化 |
| `mall-security` | JWT（HS512）、Spring Security 过滤链、权限注解、验证码、TokenStore（单会话 / 顶号 / 刷新） |
| `mall-user` | 买家账号 |
| `mall-merchant` | 商家入驻申请、店铺信息与营业状态 |
| `mall-admin` | 平台 RBAC（账号 / 角色 / 权限）、商家与商品审核、类目管理 |
| `mall-product` | SPU / SKU / 规格、文件上传、商品搜索、向量生成与混合召回 |
| `mall-order` | 地址、购物车、订单、支付、物流、退款、评价（含 AI 摘要与推荐） |
| `mall-ai` | AI 统一网关（Chat / Embedding）、Provider、内存向量库、RRF 融合 |
| `mall-gateway-web` | 启动模块（8080）、全局配置、控制器装配 |

依赖方向单向：`gateway-web → 业务模块 → security → common`；`mall-order` 依赖 `mall-product` / `mall-user`，业务模块不反向依赖启动模块。

## 环境依赖

- JDK 17、Maven 3.8+
- MySQL 8.0（库 `mall`，默认 `root/123456`）
- Redis 7（`localhost:6379`）
- MongoDB 6（`mongodb://localhost:27017/mall`）

```bash
docker compose up -d   # 启动 redis + mongo；--profile deploy 可额外启动 mysql
```

## 数据库初始化

按文件名顺序执行 [`docker/mysql/init/`](docker/mysql/init/) 下的脚本：

```bash
mysql -uroot -p123456 mall < docker/mysql/init/01-auth-schema.sql
# 02-rbac → 03-shop-product → 04-m2-seed → 05-order → 06-m3-seed
# 08-shipment → 09-refund → 10-ai → 11-review
```

> 交付版 DDL 同步存放于 [`docs/db/`](docs/db/)，包含各里程碑建表、索引与 Redis Key 设计说明。

## 环境变量

所有密钥均通过环境变量注入，仓库不保存真实密钥。常用变量：

```powershell
$env:MALL_JWT_SECRET       = '<64 字节以上的 HS512 密钥>'

# 支付宝沙箱
$env:MALL_ALIPAY_APP_ID    = '<appId>'
$env:MALL_ALIPAY_PRIVATE_KEY = '<应用私钥>'
$env:MALL_ALIPAY_PUBLIC_KEY  = '<支付宝公钥>'
$env:MALL_ALIPAY_NOTIFY_URL  = '<公网可达的回调地址>'

# 高德物流（provider=amap 时启用轨迹规划）
$env:MALL_AMAP_WEB_KEY        = '<高德 Web 服务 Key>'
$env:MALL_LOGISTICS_PROVIDER  = 'amap'

# AI（不配置时自动降级，不影响主流程）
$env:MALL_AI_ENABLED       = 'true'
$env:MALL_AI_PROVIDER      = 'openai-compat'
$env:MALL_AI_BASE_URL      = 'https://api.deepseek.com'
$env:MALL_AI_API_KEY       = '<DeepSeek Key>'
$env:MALL_AI_CHAT_MODEL    = 'deepseek-chat'

# 向量模型（语义搜索，可选；百炼 OpenAI 兼容接口）
$env:MALL_EMBED_ENABLED    = 'true'
$env:MALL_EMBED_API_KEY    = '<百炼 Key>'
$env:MALL_EMBED_MODEL      = 'text-embedding-v3'
$env:MALL_AI_SEARCH_MODE   = 'hybrid'   # keyword / hybrid / auto
```

完整配置见 [`mall-gateway-web/src/main/resources/application.yml`](mall-gateway-web/src/main/resources/application.yml)。

## 构建与运行

```bash
mvn clean package -DskipTests
java -jar mall-gateway-web/target/mall-gateway-web-1.0.0-SNAPSHOT.jar
```

- 健康检查：`GET http://localhost:8080/api/ping`
- Swagger UI：`http://localhost:8080/swagger-ui/index.html`
- 本地上传目录：`uploads/`，通过 `/static/**` 暴露

## 测试

```bash
mvn test
```

覆盖认证、RBAC、商品审核、下单拆单、库存并发、支付幂等、退款、物流、评价与 AI 推荐等主链路（MockMvc 集成测试 + Mockito 单元测试）。

## 接口文档

| 文档 | 内容 |
|---|---|
| [m1-auth-api.md](docs/api/m1-auth-api.md) | 认证 / 验证码 / Token |
| [m2-product-api.md](docs/api/m2-product-api.md) | 文件、入驻、店铺、类目、商品、审核 |
| [m3-order-api.md](docs/api/m3-order-api.md) | 地址、购物车、结算、订单 |
| [m4-payment-api.md](docs/api/m4-payment-api.md) | 支付宝沙箱支付 |
| [m5-logistics-api.md](docs/api/m5-logistics-api.md) | 发货、轨迹、确认收货 |
| [m6-refund-api.md](docs/api/m6-refund-api.md) | 退款售后 |
| [m7-logistics-map-api.md](docs/api/m7-logistics-map-api.md) | 高德地图轨迹 |
| [m9-ai-copy-api.md](docs/api/m9-ai-copy-api.md) | AI 商品文案 |
| [m10-review-api.md](docs/api/m10-review-api.md) | 评价、摘要、审核、AI 导购 |

## 演示账号

| 角色 | 账号 | 密码 |
|---|---|---|
| 买家 | `user01` ~ `user04` | `test123` |
| 商家 | `merchat01` ~ `merchat04` | `test123` |
| 平台管理员 | `admin` | `admin123` |

## 说明

- 支付使用支付宝**沙箱**，不涉及真实资金。
- AI 能力为增强项：`MALL_AI_ENABLED=false` 时走空实现，摘要回落统计摘要、推荐回落规则排序，业务不中断。
- 三端界面截图见 [`docs/images/`](../docs/images/)。
- 本项目仅用于学习与演示。
