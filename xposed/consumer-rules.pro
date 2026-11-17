
# Loaded by the framework from java_init.list, including lifecycle methods which
# have no callers inside the APK. The compile-only API must never be packaged.
-keep public class com.system.location.service.hook.PortalModule { public *; }
-adaptresourcefilecontents META-INF/xposed/java_init.list

# The framework calls these interceptors through its compile-only interface.
# Keep both sides of the SAM boundary: the interface is supplied by the
# framework at runtime, while the anonymous Kotlin implementations are in our
# APK. Renaming either side causes AbstractMethodError before the hook runs.
-keep interface io.github.libxposed.api.XposedInterface$Hooker { *; }
-keep class * implements io.github.libxposed.api.XposedInterface$Hooker { *; }

# Both native payloads resolve these Kotlin objects by their exported JNI names.
-keepclasseswithmembernames,includedescriptorclasses class com.system.location.service.jni.** {
    native <methods>;
}

# StackCleanPolicy recognizes module frames by this package prefix.
-keeppackagenames com.system.location.service.**
