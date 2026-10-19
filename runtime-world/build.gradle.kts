plugins { id("gg.grounds.minestom-conventions") }

// Worlds and maps: GroundsWorld, WorldIdentity, MapLight and MapStore. A module of its own, with
// nothing but Minestom under it, so the map service can light maps with exactly the code the
// game servers load them with, without taking the rest of the runtime along.
dependencies {
    implementation(platform("gg.grounds:grounds-dependencies:0.1.0"))

    // `GroundsWorld` sends each map's seed with `Instance.setHashedSeed`, which the 1.21.11 build
    // from the conventions does not have. 26.2 is the newest upstream release; the games and the
    // lobby run on our 26.3 fork of it, which has the same instance API.
    implementation("net.minestom:minestom:2026.10.07-26.2")
    implementation("org.slf4j:slf4j-api")

    // MapStore: the pin file is JSON, map bundles are tar.zst. commons-compress reads the tar;
    // zstd-jni is the codec it delegates to and has to be on the classpath explicitly. The same
    // versions the games used for their own copies of the map client.
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("com.github.luben:zstd-jni:1.5.7-6")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
