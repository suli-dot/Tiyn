# Room
-keep class kz.sultan.spendlimit.data.local.** { *; }

# Ktor / Supabase используют рефлексию для сериализации
-keepattributes *Annotation*, InnerClasses
-dontwarn kotlinx.serialization.**
-keep,includedescriptorclasses class kz.sultan.spendlimit.**$$serializer { *; }
-keepclassmembers class kz.sultan.spendlimit.** {
    *** Companion;
}
