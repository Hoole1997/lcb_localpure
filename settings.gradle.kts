import java.util.Properties

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

val buildConfigFile = file("build.config.properties")
val buildConfig = Properties()
if (buildConfigFile.exists()) {
    buildConfig.load(buildConfigFile.inputStream())
}

// Core SDK 仍由私有 ReMax Maven 仓库提供；这些凭据不再与 Launcher 命名耦合。
val remaxGithubUser = buildConfig.getProperty("github.user")
    ?: System.getenv("REMAX_SDK_GITHUB_USER")
    ?: System.getenv("LAUNCHER_SDK_GITHUB_USER")
    ?: System.getenv("GITHUB_ACTOR")
val remaxGithubToken = buildConfig.getProperty("github.token")
    ?: System.getenv("REMAX_SDK_GITHUB_TOKEN")
    ?: System.getenv("LAUNCHER_SDK_GITHUB_TOKEN")
    ?: System.getenv("GITHUB_TOKEN")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven {
            url = uri("https://maven.pkg.github.com/toukaRemax/remax_sdk")
            credentials {
                username = remaxGithubUser
                password = remaxGithubToken
            }
        }
    }
}

rootProject.name = "LCB_OnlineMusic"
include(":app")
//include(":core")
include(":metrics")
include(":music-sdk")
