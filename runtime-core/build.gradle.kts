plugins { id("gg.grounds.minestom-conventions") }

dependencies {
    implementation(platform("gg.grounds:grounds-dependencies:0.1.0"))

    api(project(":runtime-api"))
    implementation("gg.grounds:library-jvm-modules-module-core:0.1.0")
    implementation("net.minestom:minestom")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
