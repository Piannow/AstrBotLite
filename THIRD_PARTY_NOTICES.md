# 第三方开源组件声明 / Third-Party Notices

AstrBotLite 使用了以下开源项目与运行时。各项目的版权归其作者所有，遵循各自许可证。
本文件仅作汇总，具体条款以对应项目仓库中的 LICENSE 为准。

## Android / Kotlin 依赖

| 项目 | 用途 | 许可证 |
|---|---|---|
| AndroidX / Android Jetpack | Android 基础组件 | Apache-2.0 |
| Kotlin Standard Library | Kotlin 运行时 | Apache-2.0 |
| OkHttp 4.12.0 | HTTP / WebSocket 客户端 | Apache-2.0 |
| Okio | OkHttp 底层 I/O | Apache-2.0 |
| org.json | JSON 解析 | JSON License |
| webviewup (`io.github.jonanorman.android.webviewup:core`) | 升级系统 WebView | Apache-2.0 |
| JUnit 4.13.2 | 单元测试（仅测试期） | EPL-1.0 |

## Python 运行时与依赖（随 APK 打包）

| 项目 | 用途 | 许可证 |
|---|---|---|
| CPython 3.11 | Python 运行时（Chaquopy 提供） | PSF-2.0 |
| Chaquopy | Android 上的 Python 集成 | MIT（开源项目使用；详见 chaquo.com） |
| requests 2.31.0 | HTTP 客户端 | Apache-2.0 |
| urllib3 | requests 依赖 | MIT |
| idna | requests 依赖 | BSD-3-Clause |
| certifi | CA 证书 | MPL-2.0 |
| charset-normalizer 3.3.3 | 编码探测 | MIT |
| aiohttp 3.9.1 | 异步 HTTP | Apache-2.0 |
| attrs / frozenlist / multidict / yarl / aiosignal | aiohttp 依赖 | Apache-2.0 / MIT |
| Pillow 9.2.0 | 图像处理 | HPND |
| PyYAML 6.0.1 | YAML 解析 | MIT |
| tzdata | 时区数据库 | Apache-2.0 |
| lunar-python | 农历/节气（插件依赖） | MIT |
| pixivpy-async | Pixiv 接口（插件依赖） | MIT |

## 前端运行时资源（随 APK 打包）

| 项目 | 用途 | 许可证 |
|---|---|---|
| Babel Standalone（`assets/webruntime/babel.min.js`） | 插件 WebUI 的 ES 语法降级转译 | MIT |

## 内置插件来源与第三方数据源声明

AstrBotLite 的「内置插件」由本项目自行实现（代码以 MIT 授权），但其中部分插件在运行时会访问**第三方站点或公开接口**。
这些站点/项目的版权与条款归各自作者所有，本项目**未打包其源代码**，也不对其可用性、内容合法性负责。

| 内置插件 | 实现归属 | 外部依赖 / 数据源 | 说明 |
|---|---|---|---|
| 帮助菜单 `help` | 本项目自研 | 无 | MIT |
| 网盘助手 `netdisk` | 本项目自研 | 搜索默认使用 PanSou 公共实例 `so.252035.xyz`（[fish2018/pansou](https://github.com/fish2018/pansou)，MIT，可配置自建）；直链获取行为参考 [LinkSwift 1.1.3](https://github.com/hmjz100/LinkSwift)（AGPL-3.0）；夸克 / UC / 123 网盘使用其网页端非官方接口 | 未包含 PanSou、LinkSwift 源码；网盘接口条款以各平台为准 |
| 云播看番 `anime` | 本项目自研 | 播放源：邦宁云播 `video.isyour.love`（第三方聚合接口）；搜索与标题：Bilibili 公开接口；B 站解析逻辑参考 [bililidownloadtool 2.2.0](https://github.com/NANblogink/bilibilidownloadtool)（MIT） | 未包含上述项目源码 |
| Galgame助手 `galgame` | 本项目自研 | 数据来自鲲 Galgame 论坛 `kungal.com` 的公开 API；论坛开源项目 [kun-galgame-nuxt3](https://github.com/KUN1007/kun-galgame-nuxt3)（AGPL-3.0） | 仅调用公开接口，未包含论坛源码 |
| 游戏资源助手 `game` | 本项目自研 | 原设计数据源为 123资源库 `www.123zyk.com`（第三方站点）；当前数据源未接入，功能不可用 | 不伪造任何站点地址或直链 |
| 文生图 `text2image` | 本项目自研 | 无内置服务；调用用户自行配置的 OpenAI 兼容出图接口 | 不内置任何密钥 |
| TTS 语音合成 `tts` | 本项目自研 | 无内置服务；调用用户自行配置的 OpenAI 兼容语音接口 | 不内置任何密钥 |
| 文本替换 `wordreplace` | 本项目自研 | 无 | 纯字面替换 |
| 去除多余空行 `filter_blankline` | 本项目自研 | 无 | 纯正则处理 |
| 过滤括号内容 `filter_bracket` | 本项目自研 | 无 | 纯正则处理 |
| 过滤表情包 `filter_emoji` | 本项目自研 | 无 | 纯正则处理 |
| 人格化 `personality` | 本项目自研，已退役 | 无 | 代码保留，不再在插件清单展示 |
| 回复润色 `humanizer` | 已移除 | 无 | 仅保留退役插件 ID 兼容 |

> 若某个外部数据源/接口的条款与你的使用场景冲突，请在插件页停用对应内置插件。第三方内容（番剧、游戏、Galgame 资源等）的版权归原权利人所有。

## 生态与兼容性说明

| 项目 | 说明 | 许可证 |
|---|---|---|
| [AstrBot](https://github.com/AstrBotDevs/AstrBot) | 本项目是独立的轻量实现，**未包含** AstrBot 源代码；仅参考其公开插件接口文档，提供插件兼容层 | AGPL-3.0 |
| [AstrBot 插件集合](https://github.com/AstrBotDevs/AstrBot_Plugins_Collection) | 插件市场索引数据（仅拉取 JSON 清单，不含插件代码） | AGPL-3.0 |
| AstrBot 社区插件 | 由用户自行从插件市场安装，不随本项目分发；各插件遵循其作者声明的许可证 | 以插件为准 |
| QQ 机器人官方 API | 使用腾讯 QQ 开放平台公开接口 | 以腾讯开放平台条款为准 |

## 图标与美术资源

项目图标 `docs/icon-*.png`、`app/src/main/res/mipmap-*`、`ic_launcher_fg.xml` 为本项目原创，
随本项目以 MIT License 授权。

## 免责声明

本项目与腾讯、QQ、AstrBot 官方无隶属关系。使用本项目所产生的任何后果由使用者自行承担，
请遵守 QQ 开放平台规则及当地法律法规。
