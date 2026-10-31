# ---- Kotlinx Serialization ----
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# 序列化器与 Companion 不能被移除/改名
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.ddl.eori.**$$serializer { *; }
-keepclassmembers class com.ddl.eori.** {
    *** Companion;
}
-keepclasseswithmembers class com.ddl.eori.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- OkHttp / Okio ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Room ----
-keep class androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# ---- Compose ----
-dontwarn androidx.compose.**

# ---- 枚举（Room TypeConverter 通过 valueOf 还原）----
-keepclassmembers enum com.ddl.eori.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---- 数据模型（Room / 序列化反射兜底）----
-keep class com.ddl.eori.data.local.** { *; }
-keep class com.ddl.eori.data.remote.** { *; }
