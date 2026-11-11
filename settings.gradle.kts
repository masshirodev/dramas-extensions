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
        // extensions-lib (Aniyomi's compile-only API) is published only on JitPack.
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "dramas-extensions"

include(":core")

// Every folder under src/<lang>/ is one extension, one APK.
File(rootDir, "src").listFiles()?.filter { it.isDirectory }?.sorted()?.forEach { lang ->
    lang.listFiles()?.filter { File(it, "extension.json").isFile }?.sorted()?.forEach { ext ->
        include(":src:${lang.name}:${ext.name}")
    }
}
