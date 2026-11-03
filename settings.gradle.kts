pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Artifex publishes the MuPDF Android bindings (com.artifex.mupdf:fitz) here.
        maven("https://maven.ghostscript.com/") {
            content { includeGroup("com.artifex.mupdf") }
        }
        // Tesseract for Android (the open-source OCR engine), only to compare it with Google's on test pages.
        maven("https://jitpack.io") {
            content { includeGroup("cz.adaptech.tesseract4android") }
        }
    }
}

rootProject.name = "PDFx"
include(":app")
