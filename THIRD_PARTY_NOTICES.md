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

## 生态与兼容性说明

| 项目 | 说明 | 许可证 |
|---|---|---|
| [AstrBot](https://github.com/AstrBotDevs/AstrBot) | 本项目是独立的轻量实现，**未包含** AstrBot 源代码；仅参考其公开插件接口文档，提供插件兼容层 | AGPL-3.0 |
| AstrBot 社区插件 | 由用户自行从插件市场安装，不随本项目分发；各插件遵循其作者声明的许可证 | 以插件为准 |
| QQ 机器人官方 API | 使用腾讯 QQ 开放平台公开接口 | 以腾讯开放平台条款为准 |

## 图标与美术资源

项目图标 `docs/icon-*.png`、`app/src/main/res/mipmap-*`、`ic_launcher_fg.xml` 为本项目原创，
随本项目以 MIT License 授权。

## 免责声明

本项目与腾讯、QQ、AstrBot 官方无隶属关系。使用本项目所产生的任何后果由使用者自行承担，
请遵守 QQ 开放平台规则及当地法律法规。
