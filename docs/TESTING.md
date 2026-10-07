# 测试运行说明

## 测试目录

| 目录 | 用途 |
| --- | --- |
| `agent/tests/` | 记忆窗口与预算、AgentLoop、参数边界、写操作结果保护、网关适配及评测程序测试 |
| `agent/tests/fixtures/` | 合成案例、冻结 Prompt、跨语言契约与最小缺陷重现样例 |
| `BE/src/test/` | 业务校验、会话契约、上下文解析、请求账本与显式集成测试 |
| `FE/tests/` | 会话切换、旧回包、身份变化和 requestId 重试行为 |

测试中的假密钥、示例手机号与预期响应用于验证参数和脱敏行为，不是业务用户数据。

## Agent 离线测试

在安装 `agent/requirements.txt` 的虚拟环境中执行：

```powershell
cd agent
python tests/run_session_memory_tests.py
python tests/run_agent_contract_tests.py
python tests/run_prompt_ab_tests.py
```

这些入口使用模型/工具替身，并通过 guard 禁止 dotenv 访问、外部网络和业务服务调用。历史 Prompt 冻结快照与当前实现不匹配时，相关用例可按既有条件跳过。

## 前端测试

```powershell
cd FE
npm ci
npm test
```

使用 Node.js 内置测试运行器，不需要启动微信开发者工具。

## Java 单元测试

使用 Java 17、Maven，在后端目录执行指定的单元测试：

```powershell
cd BE
mvn '-Dtest=AgentManualContextResolverTest,PythonAgentClientContextTest,ServiceOrderControllerValidationTest,SessionMemoryContractTest,SessionMemoryExecutionControllerTest,SessionMemoryQAServiceTest,SessionMemoryWindowTest' test
```

`SessionMemoryContractTest` 使用相邻 Agent 目录中的共享 JSON fixture，请保留当前目录布局。MongoDB、Python HTTP 联调和引擎重启用例需要独立测试服务与显式参数，不属于上述离线入口。

## 评测与生成文件

真实模型评测源码保留在 `agent/tests/`，只有显式启用才调用模型；需要自行提供测试凭据和预算。业务工具应使用替身，避免对实际用户资料写入。

测试与构建可能生成 `BE/target`、`reports`、`eval-results`、缓存或日志；它们由 `.gitignore` 排除，不应随源码提交。

## 发布目录检查

从仓库根目录运行：

```powershell
python scripts/check_public_export.py
docker compose --env-file .env.example config --quiet
```

发布检查脚本遇到真实 dotenv 文件时只报告文件名，不读取内容。
