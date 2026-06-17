plugins {
    id("gg.grounds.minestom-conventions")
    application
}

dependencies {
    implementation(project(":runtime-core"))
    implementation("net.minestom:minestom:2026.06.05-26.1.2")
    implementation("org.slf4j:slf4j-api:2.0.18")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    runtimeOnly("gg.grounds:plugin-agones-minestom:0.5.1")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.18")
}

application { mainClass.set("gg.grounds.runtime.example.MainKt") }
