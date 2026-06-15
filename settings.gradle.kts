rootProject.name = "grounds-minestom-runtime"

include("runtime-api", "runtime-core", "runtime-testkit", "examples:minigame-agones")

pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/groundsgg/*")
            credentials {
                username = providers.gradleProperty("github.user").get()
                password = providers.gradleProperty("github.token").get()
            }
        }
        gradlePluginPortal()
    }
}
