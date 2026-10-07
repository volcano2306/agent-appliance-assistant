# Python Agent

负责 Prompt、模型请求、RAG、Function Calling、执行结果保护与会话摘要；Java 负责业务写入和会话持久化。

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
Copy-Item .env.example .env
# 在本地模板中填写自己的模型字段与 AGENT_INTERNAL_TOKEN
uvicorn app.main:app --host 127.0.0.1 --port 8001
```

索引入口：`python -m app.indexing`。MongoDB 需已有 fragments。记忆默认关闭，阈值 1500、最近五轮、摘要 600；启用需确认上下文容量并同步 Java 配置。

离线回归：

```powershell
python tests/run_session_memory_tests.py
python tests/run_agent_contract_tests.py
python tests/run_prompt_ab_tests.py
```

这些入口禁止 dotenv、业务网络与模型调用。真实模型测试脚本必须显式启用，可能产生费用，不属于默认验证。详见根 [配置](../docs/CONFIGURATION.md)、[架构](../docs/ARCHITECTURE.md) 和 [启动说明](../docs/SETUP.md)。
