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

# Preserve file names and line numbers for readable stack traces
-keepattributes SourceFile,LineNumberTable

# Replace source file paths with just "SourceFile" to avoid leaking build paths
-renamesourcefileattribute SourceFile

# Disable obfuscation entirely
-dontobfuscate


-keep class dev.davidv.bergamot.DetectionResult { *; }
#-keep class dev.davidv.bergamot.LangDetect { *; }
#-keep class dev.davidv.bergamot.NativeLib { *; }

# Keep Tarkka native library integration
-keep class dev.davidv.translator.TarkkaBinding { *; }
-keep class dev.davidv.translator.TarkkaData** { *; }
-keep class dev.davidv.translator.AggregatedWord { *; }
-keep class dev.davidv.translator.PosGlosses { *; }
-keep class dev.davidv.translator.Gloss { *; }
-keep class dev.davidv.translator.WordWithTaggedEntries { *; }
-keep class dev.davidv.translator.WordWithTaggedEntries$WordTag { *; }
-keep class dev.davidv.translator.WordEntryComplete { *; }
-keep class dev.davidv.translator.Sense { *; }

# Keep Tesseract native library integration
-keep class dev.davidv.translator.TesseractBinding { *; }
-keep class dev.davidv.translator.TesseractOCR { *; }
-keep class dev.davidv.translator.DetectedWord { *; }
-keep class dev.davidv.translator.PageSegMode { *; }
-keep class dev.davidv.translator.TesseractData** { *; }

# Keep Mucab native library integration
-keep class dev.davidv.translator.MucabBinding { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}
