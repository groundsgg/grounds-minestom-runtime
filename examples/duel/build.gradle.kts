plugins {
    id("gg.grounds.minestom-conventions")
    application
}

// gg.grounds.vanilla (the PvP combat: damage, knockback, death, …) is published
// to its OWN GitHub Packages repo, which the groundsgg/* wildcard the root build
// uses does NOT serve. minestom-lobby depends on the same artifact the same way.
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/groundsgg/grounds-vanilla")
        credentials {
            username = providers.gradleProperty("github.user").get()
            password = providers.gradleProperty("github.token").get()
        }
    }
}

dependencies {
    implementation(platform("gg.grounds:grounds-dependencies:1.0.0"))

    implementation(project(":runtime-core"))
    // The MatchHost gRPC server + the Agones `matches` counter. This is what
    // lets the matchmaker push an allocated match onto this server.
    implementation(project(":runtime-match"))
    implementation("net.minestom:minestom")
    // Vanilla PvP: one install() call wires combat, knockback, damage and death
    // onto the global event node. Same version minestom-lobby runs.
    implementation("gg.grounds.vanilla:vanilla-core:0.2.0")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // The Agones module: under GROUNDS_MATCHMAKING=1 it marks the GameServer
    // Ready exactly once and then hands off — it must not ready the server back
    // into the pool while a match is being set up.
    runtimeOnly("gg.grounds:plugin-agones-minestom:0.6.0")
    runtimeOnly("org.slf4j:slf4j-simple")
}

application { mainClass.set("gg.grounds.duel.DuelServerKt") }
