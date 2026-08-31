-keepattributes *Annotation*

# libtorrent4j SWIG and native JNI bindings
-keep class org.libtorrent4j.** { *; }
-keep class org.libtorrent4j.swig.** { *; }
-dontwarn org.libtorrent4j.**