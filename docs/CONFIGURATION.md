# 配置与会话压缩说明

## 需要自行填写的字段

所有 `.env.example` 都是公共模板，可以查看；本地 `.env` 不应提交。Java 使用环境变量，Python 本机运行在 `agent/` 下加载自己的 `.env`，Docker 使用根模板提供环境变量。

| 能力 | Agent 字段 | Java / 前端字段 | 必须填写 |
| --- | --- | --- | --- |
| 主模型 | `DEEPSEEK_API_KEY`、`DEEPSEEK_BASE_URL`、`DEEPSEEK_MODEL` | `NEWAPI_API_KEY`、`NEWAPI_BASE_URL`、`NEWAPI_MODEL` | AI 问答需要；密钥不随源码 |
| 服务间验证 | `AGENT_INTERNAL_TOKEN` | 同名字段 | 填入一致的随机令牌 |
| MongoDB | `MONGODB_URI`、`MONGODB_DATABASE` | `MONGODB_URI` | 默认本机；需实际运行服务 |
| 向量库 | `QDRANT_URL`、可选 `QDRANT_API_KEY` | 无 | 向量召回需要索引 |
| 搜索 | `SERPAPI_API_KEY` | 可选 `SEARXNG_BASE_URL` 供 Java 回退 | 按需 |
| 语音 | `TENCENT_ASR_APP_ID/SECRET_ID/SECRET_KEY` | Java 转发 Agent | 按需 |
| 微信 | 无 | 开发者工具自己的 AppID；Java `WX_APP_ID/SECRET` | 真实平台能力按需；当前登录仍为 Demo |
| 对象存储 | 无 | `OSS_ENDPOINT/ACCESS_KEY/SECRET_KEY/BUCKET/PUBLIC_URL` | 旧 OSS seed/上传路径按需；本地文件上传可独立使用 |
| 文件路径 | 无 | `APP_DATA_ROOT`、`APP_UPLOAD_DIR` | 本版默认相对路径 |
| 旧解析脚本 | 无 | `APP_PYTHON_PARSER_SCRIPT`、`APP_PYTHON_COMMAND` | 只在调用旧 `/parse` 时需要 |

保留 `DEEPSEEK_*` 是为了兼容现有代码；变量名不代表实际供应商。默认网关 `https://windhub.cc/v1`、模型 `qwen3.8-flash-next` 均可替换；使用自己的可用网关和授权密钥。

`MODEL_EXTRA_BODY` 是 JSON 对象，默认 `{}`。默认不向该网关发送 DeepSeek 专有 thinking 字段。是否支持或关闭 Qwen 思考参数，需以自己的网关协议为准，不能由空对象推断。

## 记忆参数

| 用途 | Python 字段 | Java 字段 / 环境变量 | 默认 |
| --- | --- | --- | --- |
| 功能开关 | `MEMORY_ENABLED` | `ai.memory.enabled` / `AI_MEMORY_ENABLED` | false |
| 执行状态开关 | `MEMORY_EXECUTION_ENABLED` | `ai.memory.execution-enabled` / `AI_MEMORY_EXECUTION_ENABLED` | false |
| 压缩策略 | `MEMORY_COMPRESSION_STRATEGY` | `AI_MEMORY_COMPRESSION_STRATEGY` | threshold |
| 触发阈值 | `MEMORY_TRIGGER_TOKENS` | `AI_MEMORY_TRIGGER_TOKENS` | 1500 |
| 增长间隔 | `MEMORY_MIN_GROWTH_TOKENS` | `AI_MEMORY_MIN_GROWTH_TOKENS` | 500 |
| 最近原文轮次 | `MEMORY_RECENT_TURNS` | `AI_MEMORY_RECENT_TURNS` | 5 |
| 摘要上限 | `MEMORY_SUMMARY_MAX_TOKENS` | `AI_MEMORY_SUMMARY_MAX_TOKENS` | 600 |
| 模型容量 | `MEMORY_MODEL_CONTEXT_WINDOW` | `AI_MEMORY_MODEL_CONTEXT_WINDOW` | 未设置，启用时必须确认 |

未触发时保留已有摘要与此后的完整对话；达到阈值时合并旧摘要和更早历史，保留最近五轮。摘要上限 600/1000 与触发阈值 1500 是不同参数，不是“第五轮以外历史到 600 就压缩”。

默认主输入预算 24000、回答预留 2000、安全余量 1024；摘要输入预算 8000。启用前两端要确认同一模型容量，当前默认组合至少需要 27024。这个数字来自本地预算校验，不是模型官方上下文参数。若调整预算，还需同步 Java/Python 对应字段。

本地 token 估算不是 Qwen 精确计数；实际成本以供应商 usage 为准。记忆功能默认关闭，参数含义与启用方式见上表。
