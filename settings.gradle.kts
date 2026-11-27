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
        // Shizuku API 仓库
        maven { url = uri("https://maven.rikka.app/releases") }
    }
}

rootProject.name = "PowerGuard"
include(":app")
