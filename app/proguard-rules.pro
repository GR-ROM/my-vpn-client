# Add project specific ProGuard rules here.

# JBson — keep all model DTOs (uses @Tag/@Type annotations + reflection)
-keep class su.grinev.model.** { *; }
-keepattributes *Annotation*

# Lombok — compile-time only, suppress R8 warnings about missing classes
-dontwarn lombok.**
-dontwarn javax.annotation.processing.**

# ZXing
-keep class com.journeyapps.** { *; }
-keep class com.google.zxing.** { *; }
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