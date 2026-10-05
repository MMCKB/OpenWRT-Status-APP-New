# Add project specific ProGuard rules here.
# For more details, see https://developer.android.com/build/shrink-code
-keepattributes *Annotation*

# JSch：加密实现类（Random/密码/密钥交换等）经 Class.forName 反射加载，
# R8 静态分析无法识别这些引用——整包保留（JSch 官方推荐做法）。
# 只 keep jce/bc/krb5 会漏掉根包与 jzlib/jbcrypt/jgss，导致运行时 NullPointerException。
-keep class com.jcraft.jsch.** { *; }
-dontwarn org.ietf.jgss.**
-dontwarn com.jcraft.jsch.jgss.**
-dontwarn com.jcraft.jsch.jbcrypt.**
-dontwarn com.jcraft.jsch.jzlib.**
# 整包保留会带出仅桌面端使用的类：PageantConnector（Windows JNA）与 Log4j2Logger，
# 其依赖不在 Android 类路径上，显式忽略（这些类运行时不会被调用）。
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.slf4j.**
