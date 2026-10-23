# M9 AI 商品文案生成 — 接口文档

> 里程碑：M9（AI 商品文案生成）
> 服务：`mall-gateway-web`（Spring Boot 3.3.4，端口 `8080`）
> 基础能力：复用 M8 `mall-ai` 模块（`AiService` / `AiGatewayService` / `OpenAiCompatProvider`）
> 降级策略：AI 不可用或返回空时自动回退模板生成，`source` 字段标识来源

---

## 一、概述

为商家提供 AI 辅助的商品文案生成能力，支持：

- **多类型文案**：标题、副标题、详情描述、核心卖点
- **多风格**：电商详情风、小红书种草风、极简风、专业参数风
- **空结果重试**：AI 返回空时自动扰动 prompt 重试（最多 2 次）
- **模板降级**：AI 不可用或重试失败时走模板生成，保证业务不中断

---

## 二、配置项

`application.yml` 追加（已有默认值，可通过环境变量覆盖）：

```yaml
mall:
  ai:
    enabled: ${MALL_AI_ENABLED:false}       # false 时走 NoOpAiGatewayService
    provider: ${MALL_AI_PROVIDER:mock}      # mock / openai-compat
    base-url: ${MALL_AI_BASE_URL:https://api.deepseek.com}
    api-key: ${MALL_AI_API_KEY:}            # 环境变量注入，不入库
    chat-model: ${MALL_AI_CHAT_MODEL:deepseek-chat}
    embedding-model: ${MALL_AI_EMBED_MODEL:text-embedding-v3}
    embedding-dim: 1024
    timeout-ms: ${MALL_AI_TIMEOUT_MS:30000}  # 长文案生成留足超时
    max-retries: 1
```

### DeepSeek 接入

当 `provider=openai-compat` 且 `api-key` 非空时，自动走 DeepSeek（OpenAI 兼容协议）：

```powershell
# Windows PowerShell（启动后端前执行）
$env:MALL_AI_ENABLED="true"
$env:MALL_AI_PROVIDER="openai-compat"
$env:MALL_AI_BASE_URL="https://api.deepseek.com"
$env:MALL_AI_API_KEY="sk-你的完整Key"
$env:MALL_AI_CHAT_MODEL="deepseek-chat"
$env:MALL_AI_TIMEOUT_MS="30000"
$env:MALL_AI_SEARCH_MODE="keyword"
```

或直接使用仓库内已 gitignore 的 `mall/ai.env`：

```powershell
Get-Content .\ai.env | ? { $_ -match '^[A-Z]' } | % { $kv = $_ -split '=', 2; [Environment]::SetEnvironmentVariable($kv[0], $kv[1], 'Process') }
```

注意：

- **超时**：生成 1500 tokens 通常需 10~30 秒，`MALL_AI_TIMEOUT_MS` 建议 30000，过短会稳定走模板降级。
- **Embedding**：DeepSeek 不提供 `/embeddings` 接口，语义搜索必须保持 `MALL_AI_SEARCH_MODE=keyword`；文案生成只用 `/chat/completions`，不受影响。

---

## 三、接口

### 3.1 生成商品文案

```
POST /api/merchant/product/copy/generate
Authorization: Bearer <accessToken>   # 需 ROLE_MERCHANT
Content-Type: application/json
```

#### 请求体

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | ✅ | 商品名称 |
| `categoryName` | string | ❌ | 类目名称，如"书桌" |
| `brand` | string | ❌ | 品牌 |
| `price` | number | ❌ | 价格 |
| `specs` | array | ❌ | 规格列表 `[{specName, values:[]}]` |
| `sellingPoints` | array | ❌ | 原始卖点关键词，AI 会润色 |
| `style` | string | ❌ | `ECOMMERCE` / `XIAOHONGSHU` / `MINIMALIST` / `PROFESSIONAL`，默认 `ECOMMERCE` |
| `types` | array | ❌ | `TITLE` / `SUBTITLE` / `DESCRIPTION` / `SELLING_POINTS`，默认全部 |

#### 请求示例

```json
{
  "name": "北欧风实木书桌",
  "categoryName": "书桌",
  "brand": "源氏木语",
  "price": 899.00,
  "specs": [
    {"specName": "材质", "values": ["橡木", "松木"]},
    {"specName": "尺寸", "values": ["120cm", "140cm"]}
  ],
  "sellingPoints": ["环保材质", "稳固承重", "大容量抽屉"],
  "style": "ECOMMERCE"
}
```

#### 响应

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "title": "北欧风实木书桌 环保橡木 学习办公首选 限时特惠",
    "subtitle": "一张好书桌，陪伴每一天",
    "description": "【北欧风实木书桌】\n\n精选橡木材质，环保漆面，承重稳固。\n120cm/140cm 可选，满足不同空间需求。\n大容量抽屉设计，收纳更整洁。\n品质保证，售后无忧，放心购买。",
    "sellingPoints": [
      "精选橡木，环保材质",
      "稳固承重，经久耐用",
      "大容量抽屉，收纳整洁",
      "多尺寸可选，适配空间"
    ],
    "source": "ai",
    "model": "deepseek-chat"
  }
}
```

#### 降级响应（AI 不可用时）

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "title": "源氏木语 北欧风实木书桌 书桌 限时特惠",
    "subtitle": "书桌精选，品质之选。",
    "description": "【北欧风实木书桌】\n\n品牌：源氏木语\n类目：书桌\n价格：¥899\n\n产品特点：\n· 环保材质，品质保证\n· 工艺精湛，经久耐用\n· 书桌优选\n",
    "sellingPoints": [
      "环保材质",
      "稳固承重",
      "大容量抽屉"
    ],
    "source": "template",
    "model": "template"
  }
}
```

---

## 四、降级链

```
AI 可用 + 返回有效 JSON → source=ai
AI 可用 + 返回空/无效 → 重试（扰动 prompt，最多 2 次）→ 仍失败 → source=template
AI 不可用（enabled=false 或 provider 未配置） → source=template
```

- `source=ai`：文案来自大模型，`model` 为实际模型名
- `source=template`：文案来自模板兜底，`model=template`

---

## 五、风格说明

| 风格 | 说明 | 适用场景 |
|---|---|---|
| `ECOMMERCE` | 电商详情风，卖点突出、结构清晰 | 淘宝/京东详情页 |
| `XIAOHONGSHU` | 小红书种草风，口语化、带 emoji | 内容种草 |
| `MINIMALIST` | 极简风，短平快 | 一句话卖点 |
| `PROFESSIONAL` | 专业参数风，数据准确 | 3C/家电等参数型商品 |

---

## 六、关键设计

1. **复用 M8 AI 网关**：`AiService.chat()` 已内置缓存、限流、熔断、异常降级，M9 零额外基建
2. **JSON 输出约束**：system prompt 强制 LLM 返回固定结构 JSON，便于解析
3. **空结果重试**：参考经验"失败返回空字符串而非抛异常"，对空内容做 trim+empty 判定并纳入重试
4. **prompt 扰动**：重试时追加"请重新生成"提示，避免相同输入重复失败
5. **模板兜底**：每个风格都有对应模板，AI 全挂时仍能产出可用文案
