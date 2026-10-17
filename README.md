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

```mermaid
flowchart LR
    U["微信小程序<br/>用户提问"] --> J["Java / Spring Boot<br/>QAService：接收请求、读取会话"]
    J --> P["Python / FastAPI<br/>参数校验与请求解析"]
    P --> C["组装模型上下文<br/>System Prompt + Few-shot<br/>历史消息 + 当前问题"]
    C --> L["调用 LLM<br/>常规调用最多 4 次"]
    L --> D{"是否返回<br/>工具调用？"}

    D -->|否| O["处理最终回答<br/>JSON 解析、字段校验<br/>写操作结果一致性检查"]
    D -->|是| T["工具分发<br/>解析工具名称与参数<br/>校验并执行"]

    subgraph TOOLS["工具层"]
        R["说明书检索"]
        F["故障排查<br/>结合型号、现象与说明书证据"]
        W["联网搜索与商品推荐<br/>SerpApi Google Search / Shopping"]
        B["业务操作<br/>创建报修单、加入我的说明书"]
    end

    T --> R
    T --> F
    T --> W
    T --> B

    subgraph RAG["RAG 混合检索"]
        Q["检索 query<br/>结合说明书范围过滤"]
        BM["BM25 关键词召回<br/>Top 20"]
        V["Query Embedding<br/>Qdrant 向量召回 Top 20"]
        RR["RRF 融合<br/>两路等权 1:1"]
        K["最终 Top 10<br/>组织片段、页码与来源"]

        Q --> BM
        Q --> V
        BM --> RR
        V --> RR
        RR --> K
    end

    R --> Q
    F --> Q

    M[("MongoDB<br/>说明书元数据、fragments")]
    QD[("Qdrant<br/>片段向量与关联标识")]

    M --> BM
    QD --> V
    QD -.->|fragmentId 关联| M

    B --> JB["Java 业务接口<br/>登录、权限及业务校验"]
    JB --> BR["返回真实操作结果<br/>成功、失败或结果不确定"]

    K --> TR["工具结果追加至上下文"]
    W --> TR
    BR --> TR
    TR --> N{"已完成第 4 次<br/>常规模型调用？"}
    N -->|否| L
    N -->|是| S["第 5 次模型调用<br/>禁止继续调用工具<br/>基于已有结果总结"]
    S --> O

    O --> JP["Java 保存会话与回答"]
    JP --> A["小程序展示<br/>回答、引用与操作结果"]

    subgraph INDEX["离线知识库构建"]
        PDF["PDF 说明书"] --> TEXT["提取正文与页码"]
        TEXT --> CH["切片<br/>384 token / 重叠 64 token"]
        CH --> E["Embedding 编码"]
    end

    CH -.->|保存片段与来源| M
    E -.->|写入向量| QD

    J -.-> MEM["可选：历史摘要压缩<br/>最近 N 轮原文 + 更早历史摘要<br/>当前默认关闭"]
    MEM -.-> C
```

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
