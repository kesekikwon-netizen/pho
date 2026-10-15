# 사진측량 P0 R8 keep rules
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# JNI: native 메서드 이름이 C 쪽 심볼(Java_kr_archsurvey_pg_tag_AprilTagNative_detect)과 같아야 한다
-keep class kr.archsurvey.pg.tag.AprilTagNative { native <methods>; *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
# ARCore (consumer rules 가 있지만 명시)
-keep class com.google.ar.** { *; }
-dontwarn com.google.ar.**
# 저장 형식(JSON 직렬화 이름)과 열거형
-keep class kr.archsurvey.pg.data.** { *; }
-keepclassmembers enum kr.archsurvey.** { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
