# Add project specific ProGuard rules here.
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

# Room - keep entities and DAOs (accessed via reflection)
-keep @androidx.room.Entity class ** { *; }
-keep @androidx.room.Dao interface ** { *; }
-keep class com.rdxindia.ihbl.routrack.database.** { *; }

# Services and Receivers registered in AndroidManifest
-keep class com.rdxindia.ihbl.routrack.service.** { *; }
-keep class com.rdxindia.ihbl.routrack.BootReceiver { *; }
-keep class com.rdxindia.ihbl.routrack.AlarmReceiver { *; }
-keep class com.rdxindia.ihbl.routrack.AlarmJobService { *; }
-keep class com.rdxindia.ihbl.routrack.AlarmDialogActivity { *; }

# Utils accessed reflectively or via API callbacks
-keep class com.rdxindia.ihbl.routrack.utils.** { *; }

# Google Play Services
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# Keep line numbers for crash stack traces
-keepattributes SourceFile,LineNumberTable