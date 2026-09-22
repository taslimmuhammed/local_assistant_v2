# LiteRT-LM reaches native code and uses reflection for tool declarations.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class * { @com.google.ai.edge.litertlm.Tool <methods>; }
-keep class kotlin.Metadata { *; }
