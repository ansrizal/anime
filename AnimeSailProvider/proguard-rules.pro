# Proguard rules for CloudStream 3 / StreamCloud Plugins
-keep class com.lagradost.cloudstream3.** { *; }
-keep class * extends com.lagradost.cloudstream3.MainAPI { *; }
-keep class * extends com.lagradost.cloudstream3.plugins.Plugin { *; }
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontwarn com.lagradost.cloudstream3.**
-dontwarn org.jsoup.**
