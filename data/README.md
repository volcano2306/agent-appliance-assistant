# 演示数据

`demo-manuals.json` 是为公开副本人工编写的两份合成家电说明书样例，仅用于检索和界面展示，不是厂商真实产品文档，也不构成真实设备故障说明。

`python scripts/seed_demo.py` 会把这些资料 upsert 到 MongoDB 的 `manuals` 与 `fragments` 集合，仅使用固定 `demo-` ID，不清空其他资料。之后在 Agent 目录执行 `python -m app.indexing` 建立向量索引。

真实说明书、照片、品牌 Excel/Word 和用户上传资料未打包。需要完整阅读体验时，请加入自己有权使用的 PDF/图片，并配置 Java 的 `APP_DATA_ROOT`。原导入器支持 `sms10(camera)`、`sms10(jiadian)` 等目录；结构以 ImportController/FileController 为准。

这个目录默认忽略额外运行数据，只有本 README 和合成 JSON 纳入公开源码。
