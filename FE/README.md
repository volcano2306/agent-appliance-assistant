# 微信原生小程序前端

该模块使用 JavaScript / WXML / WXSS，并非 uni-app。用微信开发者工具打开当前目录。公共配置 AppID 为 `touristappid`，需要平台能力时填入自己的 AppID；本地私有配置文件已忽略。

- `app.js`：登录状态、设置、后端地址。默认 `http://localhost:18083`。
- `pages/ai`：AI 会话、语音与异步响应隔离。
- `pages/search`：说明书关键词搜索与搜索历史，原 `pages/logs`。
- `pages/appliance`、`manual-detail`：说明书内容和阅读。
- `pages/publish`：上传；`service-orders`：工单；`profile`、`userinfo`：个人中心。
- `custom-tab-bar`、`images`：导航与 UI 资源。

```powershell
npm ci
npm test
```

UI 所需图标已保留。真机需把 localhost 改成自己的可访问服务地址，并按微信要求配置域名；当前后端登录为 Demo。详见 [功能边界](../docs/FEATURES.md) 与 [启动说明](../docs/SETUP.md)。
