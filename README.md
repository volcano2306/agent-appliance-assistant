# Agent Appliance Assistant

家电说明书智能服务小程序

面向家电说明书查找、阅读、故障咨询和售后报修场景的微信原生小程序。项目由 **Python Agent、Java 业务后端、微信小程序前端** 三部分组成，提供说明书管理、AI 问答、工具调用及会话记忆实现。

本仓库是源码整理版：保留运行源码、测试、必要图标和本地 tokenizer，移除真实密钥、个人 AppID、个人电脑路径、日志文件、构建产物、数据库内容及评测原始回包。未携带原仓库 Git 历史。

## 三个独立模块

| 目录 | 模块 | 职责 | 技术 |
| --- | --- | --- | --- |
| `agent/` | AI Agent | 模型调用、混合检索、工具编排、摘要压缩、语音服务适配 | Python、FastAPI、OpenAI SDK、MongoDB、Qdrant、FastEmbed |
| `BE/` | 软件后端 | 小程序统一业务 API、说明书/用户/工单数据、会话归属与执行状态 | Java 17、Spring Boot 3.2.6、MongoDB、PDFBox |
| `FE/` | 软件前端 | 说明书检索与阅读、AI 会话、收藏、上传、报修、个人中心 | 微信原生小程序、JavaScript、WXML、WXSS |
| `docs/` | 项目文档 | 结构、功能、架构、启动、配置、接口、测试运行与整理说明 | Markdown |
| `scripts/` | 辅助脚本 | 合成演示数据导入、发布副本检查 | Python 标准库 / PyMongo |

```mermaid
flowchart LR
    UI[微信小程序 frontend] --> API[Spring Boot backend]
    API --> Agent[FastAPI agent]
    API --> Mongo[(MongoDB 业务与会话)]
    Agent --> Model[OpenAI 兼容模型网关]
    Agent --> Mongo
    Agent --> Vector[(Qdrant 向量索引)]
    Agent --> API
    Agent --> Search[商品搜索 / 语音识别服务]
```

```mermaid
flowchart LR
    U["小程序提问"] --> J["Java 接口"]
    J --> P["FastAPI 校验"]
    P --> C["组装上下文"]
    C --> L["LLM 调用"]
    L --> D{"调用工具？"}

    D -->|否| O["响应校验"]
    D -->|是| T["工具分发"]

    subgraph TOOLS["工具层"]
        R["说明书检索"]
        F["故障排查"]
        W["搜索与推荐"]
        B["收藏与报修"]
    end

    T --> R
    T --> F
    T --> W
    T --> B

    subgraph RAG["混合检索"]
        Q["检索 Query"]
        BM["BM25 Top20"]
        E["Query 向量化"]
        V["向量 Top20"]
        RR["RRF 等权 1:1"]
        K["融合 Top10"]
        S["片段与引用"]

        Q --> BM
        Q --> E
        E --> V
        BM --> RR
        V --> RR
        RR --> K
        K --> S
    end

    R --> Q
    F --> Q

    M[("MongoDB")]
    QD[("Qdrant")]

    M --> BM
    QD --> V
    QD -.->|fragmentId| M

    W --> API["SerpApi"]
    API --> TR["追加工具结果"]

    B --> JB["Java 业务校验"]
    JB --> BR["真实操作结果"]
    BR --> TR

    S --> TR
    TR --> N{"已调用4次？"}
    N -->|否| L
    N -->|是| SUM["第5次仅总结"]
    SUM --> O

    O --> SAVE["保存会话"]
    SAVE --> A["前端展示"]

    subgraph INDEX["离线建库"]
        PDF["PDF"] --> TEXT["提取正文页码"]
        TEXT --> CH["切片 384/64"]
        CH --> EMB["Embedding"]
    end

    CH -.->|正文与来源| M
    EMB -.->|向量与标识| QD

    J -.-> MEM["可选历史摘要"]
    MEM -.-> C
```

## Agent 架构

采用 Java + Python 双服务架构：Java 提供业务接口与会话管理，Python 负责 Agent 编排、工具调用和 RAG 检索。
- **知识库构建：**按 384 token 长度、64 token 重叠切片，正文与来源存入 MongoDB，向量写入 Qdrant。
- **混合检索：**BM25 与向量检索各召回 20 条，经等权 RRF 融合后返回前 10 条及引用信息。
- **工具调用：**模型按需调用检索、故障排查、联网搜索、商品推荐、收藏及报修工具。
- **循环控制：**最多进行 4 次常规模型调用；第 4 次仍返回工具调用时，执行工具后增加第 5 次调用，仅总结已有结果。
- **上下文管理：**结合 Prompt、Few-shot、历史消息及当前问题组织上下文；历史摘要压缩为可选能力，当前默认关闭。

## 功能入口

- 说明书：分类、关键词搜索、PDF/图片阅读、上传、收藏、批注与评论。
<p align="center">
<img width="485" height="350" alt="image" src="https://github.com/user-attachments/assets/27a22c09-6334-44fd-a7e9-eb2908459c86" />
<img width="485" height="350" alt="image" src="https://github.com/user-attachments/assets/ceb89ddc-136c-46ba-bab9-edc13d5b4875" />
</p>

- AI：说明书问答、故障咨询、按意图调用五类工具，返回证据来源与页码。
<p align="center">
<img width="330" height="350" alt="image" src="https://github.com/user-attachments/assets/ee0b982f-fe21-4ddb-954e-44058385b975" />
<img width="330" height="350" alt="image" src="https://github.com/user-attachments/assets/8d9347bf-5786-470a-b0a0-d71cbd007eed" />
</p>

- 服务：收藏说明书、经确认创建报修单、查看及取消工单、消息与设备管理。
<p align="center">
<img width="485" height="350" alt="image" src="https://github.com/user-attachments/assets/ece9f99b-c8ff-4ea3-9acf-d0a099810809" />
</p>

- 会话：会话列表、标题修改与删除；实现按阈值批量压缩及重复请求控制。

功能实现和外部依赖的边界见 [功能说明](docs/FEATURES.md)。登录目前为 Demo 实现；会话记忆默认关闭，启用前需要配置双方参数并确认模型上下文容量。

## 开始使用

1. 按 [启动说明](docs/SETUP.md) 准备 Java 17、Python 3.11/3.12、MongoDB 和 Qdrant，或使用根目录 Docker Compose。
2. 根据 [配置说明](docs/CONFIGURATION.md) 填写自己的模型凭据、内部调用令牌与小程序 AppID。
3. 可导入 `data/demo-manuals.json` 的合成说明书，再建立向量索引。
4. 用微信开发者工具打开 `FE/`；本机开发后端地址为 `http://localhost:18083`。

模型配置保留历史环境变量名 `DEEPSEEK_*`，实际默认模型为 `qwen3.8-flash-next`，网关地址可配置。整理版没有模型密钥，付费模型不会在测试中自动调用。

## 文档导航

- [项目结构](docs/PROJECT_STRUCTURE.md)
- [功能说明与实现边界](docs/FEATURES.md)
- [Agent 架构与执行流程](docs/ARCHITECTURE.md)
- [环境与启动步骤](docs/SETUP.md)
- [配置字段与会话压缩](docs/CONFIGURATION.md)
- [接口概览](docs/API.md)
- [测试运行说明](docs/TESTING.md)
- [清理及隐私处理说明](docs/PUBLIC_EXPORT.md)
- [第三方资源说明](THIRD_PARTY_NOTICES.md)

本项目采用 [MIT 许可证](LICENSE)。第三方资源保留各自的许可证，详见 [第三方资源说明](THIRD_PARTY_NOTICES.md)。
