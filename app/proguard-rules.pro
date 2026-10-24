# JNA reads native structures and callbacks by reflection; UniFFI's generated
# tag bindings (com.lyricsadapter.audiotags) are built on it.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep class com.lyricsadapter.audiotags.** { *; }
-dontwarn java.awt.**
