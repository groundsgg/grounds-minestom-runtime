plugins {
    id("gg.grounds.minestom-conventions")
    application
}

dependencies {
    implementation(platform("gg.grounds:grounds-dependencies:0.1.0"))

    implementation(project(":runtime-core"))
    implementation("net.minestom:minestom")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    runtimeOnly("gg.grounds:plugin-agones-minestom:0.6.0")
    runtimeOnly("org.slf4j:slf4j-simple")
}

application { mainClass.set("gg.grounds.runtime.example.MainKt") }
