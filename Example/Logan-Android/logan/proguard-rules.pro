# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /Users/baitian0521/Library/Android/sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Logan 核心类保持
-keep class com.dianping.logan.** { *; }
-dontwarn com.dianping.logan.**

# 保持 JNI 方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保持线程相关类
-keep class * extends java.lang.Thread {*;}

# 保持异常信息，便于调试
-keepattributes Exceptions, InnerClasses, Signature, Deprecated, SourceFile, LineNumberTable

# 保持枚举
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 禁止对 Logan 进行优化
-assumenosideeffects class com.dianping.logan.** {
    *;
}