pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "messnc-client"

include(
    ":modules:core:core-domain",
    ":modules:core:core-network",
    ":modules:core:core-database",
    ":modules:core:core-sync",
    ":modules:core:core-sdk",
    ":modules:messenger-crypto",
    ":integration-harness",
)
