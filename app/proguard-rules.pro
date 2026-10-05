# Add project specific ProGuard rules here.
# For more details, see https://developer.android.com/build/shrink-code
-keepattributes *Annotation*

# JSch：加密实现类（Random/密码/密钥交换等）经 Class.forName 反射加载，
# R8 静态分析无法识别这些引用，需整包保留——否则 SSH 运行时 ClassNotFoundException。
-keep class com.jcraft.jsch.jce.** { *; }
-keep class com.jcraft.jsch.bc.** { *; }
-keep class com.jcraft.jsch.krb5.** { *; }
-dontwarn org.ietf.jgss.**
