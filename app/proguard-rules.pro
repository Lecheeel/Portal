# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

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
# The AMap aggregate JAR has no consumer rules. Its map/location JNI and
# dynamically loaded implementation classes must retain their names and members.
# Tencent supplies its own consumer rules in its AARs; do not override those.
-keep class com.amap.** { *; }
-keep class com.autonavi.** { *; }

# Optional AMap implementations and its standalone math helper are absent from
# this aggregate artifact, but are referenced by code paths selected at runtime.
-dontwarn com.amap.ams.gnss.GnssSoftLocator
-dontwarn net.jafama.FastMath

-dontwarn com.tencent.bugly.**
-keep public class com.tencent.bugly.**{*;}
