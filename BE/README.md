# Java 软件后端

Java 17 / Spring Boot，提供小程序业务 API、说明书与用户数据、收藏和报修、会话存储、请求账本及 Python Agent 适配。保留现有 Java 模型回退实现。

```powershell
mvn -DskipTests package
mvn spring-boot:run
```

默认端口 `18083`。运行前需要 MongoDB、模型配置及与 Agent 一致的 `AGENT_INTERNAL_TOKEN`。配置来自当前进程环境变量，Spring Boot 不自动加载根或 Agent 的 `.env`。

数据初始化和旧自动 seed 默认关闭。旧 `/parse` 入口的外部脚本未随原仓库提供；本版已移除其个人路径，说明见 [启动文档](../docs/SETUP.md)。单元与集成测试在 `src/test`，需要 MongoDB或独立测试引擎的用例不应无配置直接运行。

详见 [接口](../docs/API.md)、[配置](../docs/CONFIGURATION.md) 和 [结构](../docs/PROJECT_STRUCTURE.md)。
