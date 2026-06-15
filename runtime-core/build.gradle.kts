plugins { id("gg.grounds.minestom-conventions") }

dependencies {
    api(project(":runtime-api"))
    implementation("net.minestom:minestom:2026.06.05-26.1.2")
    implementation("org.slf4j:slf4j-api:2.0.18")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
