# Wave News ProGuard-Regeln (Release-Builds)
# Gson-Modelle der Google-Reader-API per Reflection nicht anfassen
-keep class com.wavenews.app.data.api.** { *; }

# Retrofit/OkHttp Standard-Regeln (empfohlen)
-keepattributes Signature, InnerClasses, EnclosingMethod
-dontwarn okhttp3.**
-dontwarn retrofit2.**
