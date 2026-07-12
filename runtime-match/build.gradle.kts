// This module is gRPC-only — it receives StartMatch and hands the roster to
// whatever gamemode wired it, and never touches Minestom itself. So it builds on
// gg.grounds.grpc-conventions rather than gg.grounds.minestom-conventions;
// grpc-conventions already layers on gg.grounds.kotlin-conventions and wires the
// protoc/grpc-java codegen toolchain (see plugin-config/common and
// plugin-social/common for the same recipe against other
// library-grpc-contracts-* jars), so there is no need to hand-roll the protobuf
// Gradle plugin here.
plugins { id("gg.grounds.grpc-conventions") }

// library-grpc-contracts-match lives outside library-jvm-modules, the only repo
// the root build wires up, so this module needs the same groundsgg wildcard repo
// the root uses for its own dependencies.
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/groundsgg/*")
        credentials {
            username = providers.gradleProperty("github.user").get()
            password = providers.gradleProperty("github.token").get()
        }
    }
}

// The contract is a moving SNAPSHOT, and Gradle caches changing modules for 24
// hours by default. Without this, a proto that merged and published minutes ago
// is invisible: the build silently compiles against a stale copy of the contract.
configurations.all { resolutionStrategy.cacheChangingModulesFor(0, "seconds") }

dependencies {
    // A proto-only jar: the protobuf plugin extracts and compiles it as though it
    // were part of this module's own source set.
    protobuf("gg.grounds:library-grpc-contracts-match:main-SNAPSHOT")

    api(project(":runtime-api"))

    // grpc-conventions already adds grpc-stub/grpc-protobuf (the generated code
    // needs them to compile) and protobuf-java as compileOnly. This module runs an
    // actual gRPC server, so it additionally needs a transport (Netty) and
    // protobuf-java on the runtime classpath.
    implementation("io.grpc:grpc-netty-shaded:1.81.0")
    implementation("com.google.protobuf:protobuf-java:4.34.1")
    // Generated grpc-java code references javax.annotation.Generated.
    implementation("javax.annotation:javax.annotation-api:1.3.2")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
