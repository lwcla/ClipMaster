pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://jitpack.io") }
        maven { setUrl("https://artifact.bytedance.com/repository/pangle") }
    }
}

rootProject.name = "LwlDemo"
include(":app")
include(":base")
include(":base:general")
include(":base:hidden-api")
include(":feature:magnet-api")
include(":feature:magnet")
include(":feature:ad-api")
include(":feature:ad-debug")
include(":feature:ad-csj")
include(":feature:ad-uniad")
include(":shizuku")
