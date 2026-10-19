plugins { id("gg.grounds.minestom-conventions") }

dependencies {
    implementation(platform("gg.grounds:grounds-dependencies:0.1.0"))

    api(project(":runtime-api"))
    implementation("gg.grounds:library-jvm-modules-module-core:0.1.0")
    // `GroundsWorld` sends each map's seed with `Instance.setHashedSeed`, which the 1.21.11 build
    // from the conventions does not have. 26.2 is the newest upstream release; the games and the
    // lobby run on our 26.3 fork of it, which has the same instance API.
    implementation("net.minestom:minestom:2026.10.07-26.2")
    implementation("org.slf4j:slf4j-api")

    // The metrics endpoint. Micrometer rather than a hand-written exposition format, because its
    // JVM binders publish the same names the Quarkus services do (`jvm_memory_used_bytes`,
    // `jvm_gc_pause_seconds`, `process_cpu_usage`) — so one Grafana query covers a game server and
    // a service. Version pinned here: `grounds-dependencies` does not manage Micrometer, and this
    // repo already pins grpc and protobuf the same way. Not the `-simpleclient` flavour — it is
    // deprecated in 1.16 and compiles with warnings. HTTP is the JDK's own server, so the endpoint
    // adds no framework of its own.
    implementation("io.micrometer:micrometer-registry-prometheus:1.16.6")

    // MapStore: the pin file is JSON, map bundles are tar.zst. commons-compress reads the tar;
    // zstd-jni is the codec it delegates to and has to be on the classpath explicitly. The same
    // versions the games used for their own copies of the map client.
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("com.github.luben:zstd-jni:1.5.7-6")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
