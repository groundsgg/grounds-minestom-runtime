plugins { id("gg.grounds.base-conventions") version "0.8.0" }

allprojects {
    repositories {
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
