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
    }
}

rootProject.name = "PDFx"
include(":app")
