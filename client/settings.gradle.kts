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
    ":modules:messenger-crypto",
    ":integration-harness",
)
