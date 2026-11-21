# AstrBotLite

> 在 Android 手机上运行 QQ 官方机器人，并兼容 AstrBot 社区插件生态的轻量客户端。

![License](https://img.shields.io/badge/license-MIT-blue.svg)
![Coding](https://img.shields.io/badge/code-100%25%20Web%20Coding-7c5cff.svg)
![Release](https://img.shields.io/badge/release-v1.0.0-success.svg)
![Android](https://img.shields.io/badge/Android-7.0%2B-3ddc84.svg)

<p align="center">
  <img src="docs/icon-512.png" width="140" alt="AstrBotLite icon">
</p>

## 100% Web Coding

本项目 **100% 由 AI 通过自然语言对话（Web Coding）生成**：需求描述、代码、调试、构建脚本与文档均在对话中完成，人类负责提出需求、验收与发布。

因此：

- 代码风格可能不统一，但每个功能都有明确的现实目标
- 欢迎提交 Issue / PR 一起完善
- 发布前请自行评估稳定性，**不要直接用于生产环境**

## 版本说明

- **GitHub Release：`v1.0.0`**（首个公开发布标签）
- **测试构建：`AstrBotLite-1.7.265.apk`**（内部迭代号 1.7.265 / versionCode 435）
- `1.x` 是开发期的内部版本号，`1.0.0` 是面向 GitHub 的首个发布标签；两者指向同一份测试构建。

> 这是测试版本，功能和接口仍可能变化。升级前请备份机器人配置与插件数据。

## 功能概览

- **QQ 官方机器人接入**：WebSocket 网关 + REST 收发，支持群聊 / 单聊、被动回复与主动消息
- **AI 对话**：多 AI 池配置、人格 / 记忆 / 好感度 / 内心思考 / 知识库
- **AstrBot 插件兼容**：内置 Python 3.11 运行时，可直接安装 AstrBot 社区插件（Star 插件）
  - 支持 `@filter.command` / `command_group` / 别名 / `regex` / `event_message_type`
  - 支持 `on_llm_request` / `on_llm_response` / `on_agent_done` / `on_decorating_result` 钩子
  - 支持插件自带 `skills/`（SKILL.md）与 WebUI 视图页面
  - 支持交互按钮 `ActionRow` / `Button`（QQ markdown + keyboard）
- **消息能力**：文本、图片、语音、视频、文件、引用图片；`/撤回` 撤回机器人上一条消息
- **插件市场**：AstrBot 插件市场镜像 + 本地插件安装 / 卸载 / 配置
- **版本库**：任务 / 分支 / 提交 / 差异 / 回退 / 撤销（内置轻量 VCS）
- **其他**：TTS、文生图 / 图生图、番剧 / 网盘 / Galgame / 游戏资源等内置能力

## 安装

1. 到 [Releases](../../releases) 下载 `AstrBotLite-1.7.265.apk`
2. 在 Android 7.0+ 设备上安装（需要允许“安装未知来源应用”）
3. 打开 App，按向导填写 QQ 机器人 AppID / Secret、AI 接口地址与密钥
4. 在「插件」页安装需要的 AstrBot 插件

> 需要先在 [QQ 开放平台](https://q.qq.com/) 创建机器人，并开启群聊 / 单聊消息与互动事件权限。
> 撤回群消息、按钮消息等能力需要平台授予对应权限。

## 从源码构建

环境要求：

- JDK 17
- Android SDK 34
- Gradle 8.7（或使用仓库内 wrapper）
- 完整版 CPython 3.11（Chaquopy 构建需要）
- Windows 构建机（当前脚本按 Windows 路径配置）

```powershell
$env:JAVA_HOME = "<你的 JDK17 路径>"
.\gradlew.bat clean assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

> `app/build.gradle` 中 Chaquopy 的 `buildPython` 指向本机 Python 路径，构建前请改成你自己的路径。

## 内置插件与第三方来源

内置插件代码均由本项目自行实现（MIT 授权），但部分插件运行时会访问第三方站点或公开接口，本项目**未打包其源码**：

| 内置插件 | 外部来源 |
|---|---|
| Galgame助手 | 数据来自鲲 Galgame 论坛 `kungal.com`（开源项目 [kun-galgame-nuxt3](https://github.com/KUN1007/kun-galgame-nuxt3)，AGPL-3.0） |
| 云播看番 | 播放源为邦宁云播 `video.isyour.love`；B 站解析参考 [bililidownloadtool](https://github.com/NANblogink/bilibilidownloadtool)（MIT） |
| 网盘助手 | 搜索默认用 PanSou 公共实例（[fish2018/pansou](https://github.com/fish2018/pansou)，MIT，可自建）；直链行为参考 [LinkSwift](https://github.com/hmjz100/LinkSwift)（AGPL-3.0） |
| 游戏资源助手 | 原数据源为 123资源库 `www.123zyk.com`；当前数据源未接入，功能不可用 |
| 文生图 / TTS 语音合成 | 无内置服务，调用用户自行配置的 OpenAI 兼容接口 |
| 帮助菜单 / 文本替换 / 过滤括号内容 / 过滤表情包 / 去除多余空行 | 纯本地处理，无外部依赖 |
| 人格化 / 回复润色 | 已退役（人格化代码保留但不在清单展示，回复润色已移除） |

完整归属与许可证说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## AstrBot 插件兼容范围

已适配大部分常用插件 API：

- 命令注册：`@filter.command`、`command_group`、别名、`priority`
- 事件监听：`event_message_type`、`platform_adapter_type`
- 正则触发：`@filter.regex`
- LLM 钩子：`on_llm_request`、`on_llm_response`、`on_agent_done`、`on_decorating_result`
- 按钮：`ActionRow` / `Button` / `@filter.button_action`
- 配置：`_conf_schema.json`（string/text/int/float/bool/object/list/dict/template_list/file）
- 技能：插件 `skills/*/SKILL.md`
- 页面：`views/<name>/index.html` + `register_web_api`

已知限制：

- 插件 `requirements.txt` 不会在设备上安装（依赖需预置进 APK）
- `@filter.llm_tool` 仅保证不崩溃，不会注册为模型工具
- `on_using_llm_tool` / `on_llm_tool_respond` / `after_message_sent` / `on_plugin_error` 不触发
- SSE（`subscribeSSE`）不支持
- `permission_type(ADMIN)` 暂不鉴权
- 自定义按钮需要 QQ 平台开通对应权限，未开通时自动降级为纯文本

## 源码完整性

当前版本的**全部源码**均在本仓库中，包括：

- `app/`：Android 客户端全部 Kotlin 源码、资源（布局 / 图标 / 主题）、Python 运行时桥接（`assets/apy/astralite_shim.py`）、内置插件清单（`assets/plugins/catalog.json`）
- `converter-app/`：AstrBot 插件转换工具（独立调试用 App）源码
- `gradle/`、`build.gradle`、`settings.gradle`、`gradlew.bat`、`gradle.properties`：构建脚本
- `docs/`：图标源图与 Release 说明

以下内容**不属于源码**，故未提交：

- 构建产物（`build/`、`*.apk`、`*.log`）
- 本机工具链（`tools/` 下的 JDK / Gradle / Python）
- 运行时用户数据（`assets/_data/`，含聊天与插件数据库，涉及隐私）
- 历史备份与临时目录（`*.bak`、`_recovery/`、`_src_backup*/`、`tmp_*`）

## 免责声明

- 本项目与腾讯、QQ、AstrBot 官方无隶属关系
- 请遵守 QQ 开放平台规则及当地法律法规，勿用于骚扰、刷屏、违规内容
- 使用本项目产生的任何后果由使用者自行承担

## 许可证

本项目采用 [MIT License](LICENSE)。

第三方组件与依赖见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
