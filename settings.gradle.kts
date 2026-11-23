pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "mockey-kt"

include(
    "mockey-core",
    "mockey-api",
    "mockey-legacy",
    "mockey-server",
    "mockey-ui",
    "mockey-parity-tests",
)
