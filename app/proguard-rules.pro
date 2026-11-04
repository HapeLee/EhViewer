-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-keepattributes LineNumberTable,SourceFile
-renamesourcefileattribute SourceFile

-repackageclasses
-allowaccessmodification
-overloadaggressively

# androidx.window 的扩展接口（WindowExtensions / Sidecar）由设备固件在运行时提供，
# 编译期必然缺失；R8 默认会把它们当成错误。
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**

# commons-compress 的 zstd 解压路径需要额外的 zstd-jni，本项目未引入该依赖。
-dontwarn com.github.luben.zstd.**
