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
