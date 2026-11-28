# JNI_OnLoad resolves these methods by name.
-keep class com.droiddeck.launcher.wayland.WaylandCompositor { *; }
-keepclasseswithmembernames class org.buran.runtime.** { native <methods>; }
