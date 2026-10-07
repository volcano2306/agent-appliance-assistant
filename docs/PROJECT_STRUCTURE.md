# 项目结构说明

## 目录树

```text
agent-appliance-assistant/
├── README.md                     # 项目入口、模块划分和文档索引
├── .env.example                  # Docker Compose 公共配置模板
├── .gitignore                    # 密钥、日志、缓存和用户数据排除规则
├── docker-compose.yml            # MongoDB、Qdrant、Agent、后端
├── LICENSE                       # 项目 MIT 许可证
├── THIRD_PARTY_NOTICES.md         # 第三方资源来源和许可证说明
├── agent/                        # Python Agent，原 AI 目录
│   ├── app/
│   │   ├── main.py               # FastAPI：聊天、摘要、记忆计划、语音
│   │   ├── config.py             # 配置校验，模型及记忆预算
│   │   ├── agent.py              # Prompt、有界 AgentLoop、响应格式修复
│   │   ├── model_options.py      # 不同模型供应商的请求参数
│   │   ├── schemas.py            # 请求、响应、证据、记忆结构
│   │   ├── write_outcomes.py     # 写操作结果保护与未知状态处理
│   │   ├── memory.py             # 历史选择、摘要与预算计划
│   │   ├── memory_tokens.py      # 固定版本的本地文本 token 估算
│   │   ├── indexing.py          # MongoDB fragments → Qdrant 索引
│   │   ├── rag/                 # 混合召回、Embedding
│   │   ├── tools/               # 工具 Schema、参数规则、业务工具
│   │   ├── clients/             # Java API、搜索、腾讯语音适配
│   │   └── memory_eval_*.py      # 记忆评测辅助，不参与默认启动
│   ├── assets/deepseek-v4/       # tokenizer、原始许可证、校验说明
│   ├── tests/                   # 离线测试、合成样例、显式真实测试入口
│   ├── .env.example             # 本机 Agent 配置模板
│   ├── requirements.txt
│   ├── Dockerfile
│   └── README.md
├── BE/                      # Java 软件后端，原 BE 目录
│   ├── src/main/java/com/example/demo/
│   │   ├── controller/          # 说明书、会话、报修、消息等 HTTP 接口
│   │   ├── service/             # 业务逻辑、Agent 适配、检索与会话状态
│   │   ├── model/               # MongoDB 实体
│   │   ├── repository/          # 数据访问
│   │   ├── dto/                 # API 数据结构
│   │   └── config/              # 数据初始化、跨域、模型、对象存储
│   ├── src/main/resources/application.properties  # 环境变量配置
│   ├── src/test/                # 单元测试与显式集成测试
│   ├── pom.xml
│   ├── Dockerfile
│   └── README.md
├── FE/                     # 微信原生小程序，原 FE 目录
│   ├── app.js / app.json / app.wxss
│   ├── pages/                   # 页面，原 logs 搜索页已改名 search
│   ├── custom-tab-bar/          # 自定义底部导航
│   ├── utils/                   # 请求、设置、报修数据整理
│   ├── images/                  # UI 图标和助手标识
│   ├── tests/                   # 会话异步回包隔离测试
│   ├── project.config.json      # 公共游客配置，无原 AppID
│   ├── package.json / package-lock.json
│   └── README.md
├── data/
│   ├── README.md                # 演示数据与真实资料导入说明
│   └── demo-manuals.json        # 人工编写的合成样例
├── scripts/
│   ├── seed_demo.py             # 只写固定 demo ID 的演示数据
│   └── check_public_export.py   # 文件、路径、常见隐私模式及引用检查
└── docs/                        # 八份项目说明文档
```

## 模块边界

前端请求 Java 后端；Java 负责对外业务 API、业务写入、会话持久化。Python 负责 Agent 推理、检索和工具编排。Python 检索可以直接读取 MongoDB fragments，并查询 Qdrant；收藏和报修等业务写入通过 Java API 完成。

Java 内保留原有兼容模型调用与检索代码，作为现有回退实现，未作为无用代码删除。测试内保留评测实现与冻结的合成样例；真实回包、历史报告、运行日志均不属于源代码，因此不导出。

不包含原 Git 历史、IDE 设置、自动化助手配置、旧备份脚本、数据查看器、文档打印稿或真实说明书资料。上传时可直接把本目录作为新的仓库根目录。
