# Xposed/libxposed/Zygisk load these entry points by the names written in
# assets/xposed_init, module metadata and native code, so they must keep their names.
-keep class com.xiao.feng.loader.entry.** { *; }

# Classes and members referenced from JNI, the host process or reflection are marked @Keep.
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <methods>;
}

# The bundled bot scripts call fastjson2 through BeanShell, so no Kotlin/Java code references
# these classes directly and R8 would otherwise strip them.
-keep class com.alibaba.fastjson2.** { *; }
