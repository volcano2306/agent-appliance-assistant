# 环境与启动说明

## 运行条件

- Python 3.11 或 3.12、Java 17、Maven 3.9。
- 微信开发者工具；Node.js 用于前端测试与 npm 依赖。
- MongoDB 和 Qdrant。容器配置使用 MongoDB 7、Qdrant 1.15.1。
- 自己的 OpenAI 兼容模型网关密钥；联网推荐和语音按需配置。

本版没有真实业务数据、密钥或原个人 AppID。数据库从空库启动，可使用合成数据演示。下面命令从仓库根目录执行，除非写明先进入模块目录。

## Docker Compose

复制根目录 `.env.example` 为本地 `.env`，填写模型密钥并生成内部调用令牌：

```powershell
Copy-Item .env.example .env
python -c "import secrets; print(secrets.token_urlsafe(32))"
docker compose up -d --build
```

将生成的随机值填入 `AGENT_INTERNAL_TOKEN`。不要提交生成的 `.env`。根模板只供 Compose 使用，不会自动供本机 Maven 读取。

Compose 包括 MongoDB、Qdrant、Agent 和 Java 后端，端口只绑定本机。检查 `http://localhost:8001/health` 与 `http://localhost:18083/actuator/health`。Docker 镜像构建与服务启动由上述命令执行。

启用记忆时额外创建本地忽略文件 `compose.local.yml`：

```yaml
services:
  agent:
    environment:
      MEMORY_MODEL_CONTEXT_WINDOW: "填入服务商确认的整数"
  backend:
    environment:
      AI_MEMORY_MODEL_CONTEXT_WINDOW: "填入相同整数"
```

同时在 `.env` 设置 `MEMORY_ENABLED=true`、`MEMORY_EXECUTION_ENABLED=true`，再执行 `docker compose -f docker-compose.yml -f compose.local.yml up -d --build`。默认预算至少需要 27024 的上下文容量；这不是服务商实际容量。

## 本机分别启动

### 数据服务

```powershell
docker compose --env-file .env.example up -d mongodb qdrant
```

仅启动数据库和向量库时无需模型密钥。

### Python Agent

```powershell
cd agent
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
Copy-Item .env.example .env
```

编辑本地 `agent/.env`，填写模型字段、随机内部令牌。保持 `JAVA_BASE_URL=http://localhost:18083`，MongoDB/Qdrant 指向自己的本地服务。然后：

```powershell
uvicorn app.main:app --host 127.0.0.1 --port 8001
```

Linux/macOS 激活命令为 `source .venv/bin/activate`。服务器部署地址和对外暴露方式需自行配置。

### Java 后端

在新的终端进入 `BE/`，把以下字段填入**当前进程环境变量**，不要写回源文件：

```powershell
$env:NEWAPI_API_KEY = '填写自己的模型密钥'
$env:NEWAPI_BASE_URL = 'https://windhub.cc/v1'
$env:NEWAPI_MODEL = 'qwen3.8-flash-next'
$env:AGENT_INTERNAL_TOKEN = '填写与 Agent 相同的随机令牌'
mvn spring-boot:run
```

Spring Boot 不自动加载 `agent/.env` 或根 `.env`。本机默认数据目录为仓库 `data/`，上传目录为仓库 `uploads/`；可通过 `APP_DATA_ROOT`、`APP_UPLOAD_DIR` 修改。不要启用自动 seed 来初始化真实业务库：旧 seed 实现包含清空说明书集合的逻辑，默认关闭。

### 小程序前端

微信开发者工具打开 `FE/`。公共配置使用 `touristappid`，可做游客方式的本地界面查看；微信登录、录音等能力按平台要求换成自己的 AppID，并配置合法域名。`project.private.config.json` 已忽略，不随仓库发布。

`FE/app.js` 中 `globalData.baseUrl` 默认 `http://localhost:18083`。真机上的 localhost 指手机自身，需要改为手机可访问的开发机地址或自己的 HTTPS 服务地址。本地调试可在开发者工具设置相应的域名校验选项。

## 合成演示数据与索引

使用 Agent 虚拟环境中的 Python，从仓库根目录执行：

```powershell
python scripts/seed_demo.py
cd agent
python -m app.indexing
```

数据导入只 upsert 固定 `demo-` ID，不清空集合。`app.indexing` 会使用配置的数据库和向量库，首次向量生成可能下载 Embedding。容器场景可将脚本与合成 JSON 复制进去执行：

```powershell
docker compose cp scripts/seed_demo.py agent:/tmp/seed_demo.py
docker compose cp data/demo-manuals.json agent:/tmp/demo-manuals.json
docker compose exec agent python /tmp/seed_demo.py --data /tmp/demo-manuals.json --uri mongodb://mongodb:27017/jiadian
docker compose exec agent python -m app.indexing
```

## PDF 资料与旧解析入口

说明书上传、本地文件访问以及 `FragmentService` 的 PDFBox 文本提取代码均保留。平台说明书可通过提供自己的数据目录导入，真实 PDF 不随本版分发。

`POST /api/manuals/{id}/parse` 沿用旧的外部 Python 适配协议：把 PDF 路径、说明书 ID 作为参数调用 `APP_PYTHON_PARSER_SCRIPT`。原仓库没有该脚本，本版将个人电脑路径改为空配置，未编造替代实现。需要该入口时自行提供适配脚本；仓库内 Java `FragmentService` 已能从本地 PDF/正文提取片段，并在 Java 检索获取片段时按需构建。Python Agent 直接读取 fragments，因此上传后还需构建片段并更新 Qdrant，不能宣称整个上传索引链路已自动接通。
