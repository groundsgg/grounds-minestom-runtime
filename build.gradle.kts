plugins { id("gg.grounds.base-conventions") version "0.8.0" }

allprojects {
    repositories {
        // Minestom for Minecraft 26.3, which upstream has not released: the build the games and
        // the lobby already run on, from our fork's GitHub Packages.
        maven {
            url = uri("https://maven.pkg.github.com/groundsgg/minestom")
            credentials {
                username = providers.gradleProperty("github.user").get()
                password = providers.gradleProperty("github.token").get()
            }
            content { includeGroup("net.minestom") }
        }
        maven {
            url = uri("https://maven.pkg.github.com/groundsgg/library-jvm-modules")
            credentials {
                username = providers.gradleProperty("github.user").get()
                password = providers.gradleProperty("github.token").get()
            }
        }
    }
}
