# Room + kotlinx.serialization keep rules
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.bookies.reader.** {
    *** Companion;
}
-keepclasseswithmembers class com.bookies.reader.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Readium reflects over its parsers in places.
-keep class org.readium.** { *; }
-dontwarn org.readium.**
