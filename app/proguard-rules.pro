# MuPDF's native code looks up its Java classes, fields and methods by name.
-keep class com.artifex.mupdf.fitz.** { *; }
-dontwarn com.artifex.mupdf.**

# BouncyCastle: its provider finds algorithms, keys and certificate parts by class name.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
