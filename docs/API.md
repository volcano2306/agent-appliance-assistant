# 接口概览

Java 默认端口 `18083`，Python 默认端口 `8001`。外部小程序通过 Java 访问业务；Python 为内部 Agent 服务。

## Java 业务接口

| 路径 | 主要方法 | 用途 |
| --- | --- | --- |
| `/api/auth/login`、`/api/auth/phone` | POST | Demo 登录 / 模拟手机号绑定 |
| `/api/manuals`、`/api/manuals/{id}` | GET | 说明书列表 / 详情 |
| `/api/manuals/upload`、`/upload-image` | POST multipart | 上传 PDF / 封面 |
| `/api/manuals/{id}/parse` | POST | 旧外部解析脚本入口，依赖未随源码提供的脚本 |
| `/api/my-manuals`、`/{id}` | GET、POST、DELETE | 我的说明书与收藏 |
| `/api/files/images/...`、`/pdfs/...`、`/uploads/...`、`/logos/...` | GET | 本地文件访问 |
| `/api/products/search`、`/{id}` | GET | 产品搜索、详情 |
| `/api/products/scan`、`/scan2` | POST | 产品识别入口 |
| `/api/qa` | POST | AI 问答入口，字段以 `QARequest` 为准 |
| `/api/ai/conversations`、`/{id}` | GET、DELETE | 会话列表、详情、删除 |
| `/api/ai/conversations/{id}/title` | PATCH | 修改会话标题 |
| `/api/ai/transcriptions` | POST multipart | 转发语音识别 |
| `/api/service-orders` | POST、GET | 创建 / 查看工单 |
| `/api/service-orders/{id}/cancel` | PUT | 取消工单 |
| `/api/user/devices`、`/{id}` | POST、GET、PUT、DELETE | 用户设备管理 |
| `/api/annotations`、`/{id}` | GET、POST、PUT、DELETE | 批注管理 |
| `/api/interactions/...` | GET、POST、DELETE | 评论、回复、点赞与收到的互动 |
| `/api/messages/summary`、`/thread`、`/read` | GET、POST、DELETE | 消息汇总、线程、已读与删除 |
| `/api/admin/import`、`/import-brands` | GET | 手动资料导入，仅用于受控本地管理 |
| `/api/internal/agent/execution/check`、`/terminal`、`/recover-unknown` | POST | 内部执行状态协作 |

上述同组路径省略公共前缀时，前缀与该行第一个路径相同。具体参数和状态码以 `BE/src/main/java/com/example/demo/controller/` 及 DTO 为准。

## Python Agent 接口

| 路径 | 方法 | 用途 |
| --- | --- | --- |
| `/health` | GET | 服务与模型/搜索/语音配置状态，不输出密钥 |
| `/v1/agent/chat` | POST | Agent 对话，结构见 `AgentRequest/AgentResponse` |
| `/v1/agent/summarize` | POST | 摘要调用，结构见 `SummaryRequest/SummaryResult` |
| `/v1/agent/memory/plan` | POST | 记忆窗口及预算计划 |
| `/v1/transcriptions` | POST multipart | 腾讯 ASR 音频识别 |

内部调用使用 `X-Internal-Token`。Python 摘要和记忆接口要求配置非空令牌。Java 业务侧当前使用 `X-User-Id` 或请求字段识别用户，结合会话归属校验；这是 Demo 机制，尚未实现完整身份认证。

运行 Agent 后可在 `/docs` 查看 FastAPI 自动生成的请求结构。
