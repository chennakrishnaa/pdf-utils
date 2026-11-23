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
        // MuPDF comes only from Artifex's repository, and nothing else comes from it (SB-3).
        exclusiveContent {
            forRepository { maven { url = uri("https://maven.ghostscript.com") } }
            filter { includeGroup("com.artifex.mupdf") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "Offkit"
include(":app")
