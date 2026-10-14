# 0.1.52 R8 full mode keep rules (design-program/05-world-class.md #1)
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# Room: database impl, DAOs, entities, migrations (consumer rules cover most; keep ours explicitly)
-keep class * extends androidx.room.RoomDatabase { <init>(...); }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class * extends androidx.room.migration.Migration { *; }
-keep class kr.archsurvey.data.** { *; }
# Bluetooth SPP: hidden BluetoothDevice.createRfcommSocket via reflection (framework), keep our BT layer intact
-keep class kr.archsurvey.bt.** { *; }
-keep class android.bluetooth.BluetoothDevice { public *; }
# NTRIP client + engine connection layer (sockets, services)
-keep class kr.archsurvey.ntrip.** { *; }
-keep class kr.archsurvey.engine.** { *; }
# MapLibre (opt-in source set, loaded by Class.forName) + SDK
-keep class kr.archsurvey.ui.MapLibreMapSurface { <init>(...); }
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**
# map projection/tiles, JSON (org.json) serialization models and persisted enums
-keep class kr.archsurvey.map.** { *; }
-keepclassmembers enum kr.archsurvey.** { *; }
-keep class kr.archsurvey.**.*Json* { *; }
# kotlinx.serialization (not used today; harmless if added later)
-keepclassmembers class kr.archsurvey.** { *** Companion; }
-keepclasseswithmembers class kr.archsurvey.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn kotlinx.serialization.**
# Google Play services auth (Drive sign-in)
-keep class com.google.android.gms.auth.** { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
