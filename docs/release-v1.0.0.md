# AstrBotLite v1.0.0

首个公开发布版本。

## 发布信息

- **Release 标签**：`v1.0.0`
- **测试构建**：`AstrBotLite-1.7.266.apk`（内部版本 1.7.266 / versionCode 436）
- **性质**：测试版本（Test Build），功能与接口仍可能调整

## 本版亮点

- QQ 官方机器人：群聊 / 单聊消息、主动消息、文本 / 图片 / 语音 / 视频 / 文件
- 新增消息撤回：发送 `/撤回` 可撤回机器人上一条消息（2 分钟内）
- 新增交互按钮：兼容 AstrBot `ActionRow` / `Button`，支持回调按钮与链接按钮
- AstrBot 插件兼容升级：
  - 命令别名、命令组、`priority`
  - `event_message_type` / `platform_adapter_type` / `filter.regex`
  - `on_agent_done` / `on_decorating_result` 钩子桥接
  - 插件自带 `skills/`（SKILL.md）自动接入
  - 新钩子装饰器不再导致插件导入崩溃
- 修复插件市场安装的 Python 插件触发方式错误（现在能真正收到消息）
- 完善应用图标：全新 2×2 色块（白 / 靛蓝 / 黑）硬边风格，自适应矢量图标 + 各密度 PNG + TV 横幅
- 移除设置页的「教程」「免费接入」占位入口
- 设备兼容：联通智家 IP502H 机顶盒完整实测通过

## 安装

1. 下载下方 APK
2. Android 7.0+ 安装
3. 按向导填写 QQ 机器人 AppID / Secret 与 AI 接口配置

## 已知限制

- 插件 `requirements.txt` 不会在设备端安装
- `@filter.llm_tool` 不会注册为模型可调用工具
- SSE 实时推送不支持
- `permission_type(ADMIN)` 暂不鉴权
- 自定义按钮依赖 QQ 平台权限，未开通时降级为纯文本

## 校验

请以 Release 附件为准。

- 文件：`AstrBotLite-1.7.266.apk`（`AstrBotLite-1.7.265.apk` 仍保留在 Release 附件中）
- 大小：52,412,934 字节
- SHA-256：`b5e2e9472ddad961b3e14827cb23c00cc9d362a0083878360f156b741f5a2b7a`

