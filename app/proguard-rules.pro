# MuPDF's native code looks up its Java classes, fields and methods by name.
-keep class com.artifex.mupdf.fitz.** { *; }
-dontwarn com.artifex.mupdf.**
