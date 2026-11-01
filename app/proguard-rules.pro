# Wispr by Deepu Gupta. Copyright 2026 Deepu Gupta. SPDX-License-Identifier: Apache-2.0
# Author / licence info (Owner) stays inside the APK for the About screen.
-keep class com.deepugupta.wispr.Owner { *; }
-keepattributes *Annotation*
-keepclassmembers class com.deepugupta.wispr.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class com.deepugupta.wispr.Panel$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.deepugupta.wispr.BubbleService
-keep class com.deepugupta.wispr.RetryReceiver
-keep class com.deepugupta.wispr.UpdateReceiver
-renamesourcefileattribute WisprByDeepuGupta
-keepattributes SourceFile,LineNumberTable
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
