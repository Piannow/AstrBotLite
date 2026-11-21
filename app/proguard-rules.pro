# 保留应用 core 包与网络库全量：核心逻辑与 OkHttp/Okio 保持原始类名
# （沿用历史发布配置，防止混淆导致的网络栈/调试异常）
-keep class com.astrbot.lite.core.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# 第三方库警告抑制
-dontwarn okhttp3.**
-dontwarn okio.**