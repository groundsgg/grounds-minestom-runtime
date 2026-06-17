package gg.grounds.runtime.example

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExampleAgonesDependencyTest {
    @Test
    fun `example uses a provider-enabled agones module release`() {
        val projectRoot = findProjectRoot()
        val buildFile = projectRoot.resolve("examples/minigame-agones/build.gradle.kts").readText()
        val groundsConfig = projectRoot.resolve("grounds.yaml").readText()

        val gradleVersion =
            requireNotNull("""plugin-agones-minestom:(\d+\.\d+\.\d+)""".toRegex().find(buildFile))
                .groupValues[1]
        val groundsVersion =
            requireNotNull(
                    """plugin-agones@v(\d+\.\d+\.\d+):plugin-agones-minestom\.jar"""
                        .toRegex()
                        .find(groundsConfig)
                )
                .groupValues[1]

        assertEquals(gradleVersion, groundsVersion)
        assertTrue(
            SemanticVersion.parse(gradleVersion) >= SemanticVersion(0, 6, 0),
            "plugin-agones-minestom $gradleVersion does not expose the Minestom module provider",
        )
    }

    private fun findProjectRoot(): Path {
        val start = Path.of("").toAbsolutePath()
        return generateSequence(start) { it.parent }
            .first {
                it.resolve("grounds.yaml").exists() && it.resolve("settings.gradle.kts").exists()
            }
    }
}

private data class SemanticVersion(val major: Int, val minor: Int, val patch: Int) :
    Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(
            this,
            other,
            SemanticVersion::major,
            SemanticVersion::minor,
            SemanticVersion::patch,
        )

    companion object {
        fun parse(value: String): SemanticVersion {
            val parts = value.split(".").map(String::toInt)
            return SemanticVersion(parts[0], parts[1], parts[2])
        }
    }
}
