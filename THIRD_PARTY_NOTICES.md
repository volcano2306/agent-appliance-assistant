# 第三方资源

## 本地 tokenizer

`agent/assets/deepseek-v4/tokenizer.json` 为原项目随附的固定版本文本 tokenizer。原许可证保留在同目录 `LICENSE`，来源、版本和 SHA-256 见该目录 README。文件未修改，用于离线 token 估算，不包含模型权重。

## MingCute 图标与字体

前端依赖 `mingcute_icon`，锁定版本 2.9.71；`package-lock.json` 标注 Apache 2.0。前端 UI 图标/字体沿用原项目资源，详见 [MingCute 官方项目](https://github.com/Richard9394/MingCute)。附带标准 [Apache 2.0 许可证文本](third_party/licenses/Apache-2.0.txt)。此处未把整个项目改授为 Apache 2.0。

## 应用依赖

Python 与 Java/npm 依赖以 `agent/requirements.txt`、`BE/pom.xml`、`FE/package-lock.json` 为准，安装或分发依赖包时适用各自许可证。本公开副本不包含虚拟环境、node_modules、Maven 缓存、模型权重或数据库镜像内容。

本项目采用根目录 [MIT 许可证](LICENSE)。本文件记录第三方资源来源；这些资源仍适用各自的原始许可证。
