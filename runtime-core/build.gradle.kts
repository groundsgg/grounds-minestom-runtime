plugins { id("gg.grounds.minestom-conventions") }

dependencies {
    implementation(platform("gg.grounds:grounds-dependencies:0.1.0"))

    api(project(":runtime-api"))
    implementation("gg.grounds:library-jvm-modules-module-core:0.1.0")
    implementation("net.minestom:minestom")
    implementation("org.slf4j:slf4j-api")

    // The metrics endpoint. Micrometer rather than a hand-written exposition format, because its
    // JVM binders publish the same names the Quarkus services do (`jvm_memory_used_bytes`,
    // `jvm_gc_pause_seconds`, `process_cpu_usage`) — so one Grafana query covers a game server and
    // a service. Version pinned here: `grounds-dependencies` does not manage Micrometer, and this
    // repo already pins grpc and protobuf the same way. Not the `-simpleclient` flavour — it is
    // deprecated in 1.16 and compiles with warnings. HTTP is the JDK's own server, so the endpoint
    // adds no framework of its own.
    implementation("io.micrometer:micrometer-registry-prometheus:1.16.6")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
