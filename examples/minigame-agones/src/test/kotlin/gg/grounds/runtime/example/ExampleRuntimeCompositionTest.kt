package gg.grounds.runtime.example

import gg.grounds.runtime.core.GroundsServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExampleRuntimeCompositionTest {
    @Test
    fun `example runtime explicitly activates agones and example modules`() {
        val server = buildExampleServer()

        assertEquals(
            listOf("grounds.agones", "grounds.example-minigame"),
            server.installedModuleIds(),
        )
    }
}

private fun GroundsServer.installedModuleIds(): List<String> {
    val modulesField = GroundsServer::class.java.getDeclaredField("modules")
    modulesField.isAccessible = true
    val modules = modulesField.get(this) as List<*>

    return modules.map { installed ->
        val idField = checkNotNull(installed).javaClass.getDeclaredField("id")
        idField.isAccessible = true
        idField.get(installed) as String
    }
}
