# 保留 ReqSourceAdd，允许混淆
-keep,allowobfuscation class com.livetv.tv.data.ReqSourceAdd {
    *;
    <init>(...);
}

# 保留 data class 字段和必要方法
-keepclassmembers class com.livetv.tv.data.** {
    private <fields>;
    public <fields>;
    <init>(...);
    *** component*();
    *** copy(...);
}
-keepclassmembers class com.livetv.tv.legacy.data.** {
    private <fields>;
    public <fields>;
    <init>(...);
    *** component*();
    *** copy(...);
}
-keepclassmembers class com.livetv.tv.legacy.models.** {
    private <fields>;
    public <fields>;
    <init>(...);
    *** component*();
    *** copy(...);
}

# 保留 decoder，允许混淆
-keep,allowobfuscation class com.livetv.tv.SourceDecoder {
    public static <methods>;
    <init>(...);
}
-keep,allowobfuscation class com.livetv.tv.SourceEncoder {
    public static <methods>;
    <init>(...);
}

# Gson 相关
-keep class com.google.gson.** { *; }
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keep class com.livetv.tv.data.ReqSources { <fields>; <init>(...); }
-keep class com.livetv.tv.data.Source { <fields>; <init>(...); }
-keep class com.livetv.tv.data.TV { <fields>; <init>(...); }
-keep class com.livetv.tv.data.EPG { <fields>; <init>(...); }
-dontwarn com.google.gson.**

# 日志移除
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# 必要库
-keep class okhttp3.OkHttpClient { *; }
-keep class okhttp3.Request { *; }
-keep class okhttp3.Response { *; }
-keep class androidx.media3.** { *; }
-keep class com.bumptech.glide.** { *; }
-keep class com.google.zxing.** { *; }
-keep class fi.iki.elonen.** { *; }
-keep class kotlin.coroutines.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class com.livetv.tv.databinding.*Binding { *; }
-keepclassmembers class com.livetv.tv.databinding.*Binding {
    public <methods>;
    <init>(...);
}

# X5 WebView
-keep class com.tencent.smtt.** { *; }
-dontwarn com.tencent.smtt.**

# ViewModelUtils
-keep class com.livetv.tv.Utils.ViewModelUtils {
    public static <methods>;
    <init>(...);
}

# mytv1 MainActivity
-keep class com.livetv.tv.legacy.MainActivity { *; }