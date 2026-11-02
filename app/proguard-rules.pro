# Everything the app needs is referenced directly or from the manifest; the
# MapLibre and AndroidX AARs ship their own consumer rules.
# Keep real names and line numbers so the System check crash log is readable.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# Flutter (mmi_ui): the engine refers to Play Store split-install classes MMI never uses.
-dontwarn com.google.android.play.core.**
-keep class io.flutter.** { *; }
