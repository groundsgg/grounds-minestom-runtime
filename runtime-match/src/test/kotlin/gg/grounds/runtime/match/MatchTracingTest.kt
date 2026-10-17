package gg.grounds.runtime.match

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MatchTracingTest {
    @Test
    fun `no endpoint is the no-op tracer`() {
        val tracing = MatchTracing.fromEnv(mapOf("OTEL_SERVICE_NAME" to "survivalgames"))

        assertSame(MatchTracing.NOOP, tracing)
        assertFalse(tracing.enabled)
        // A no-op span records nothing and ends without complaint.
        val span = tracing.tracer.spanBuilder("match duel").startSpan()
        assertFalse(span.isRecording)
        span.end()
        tracing.close()
    }

    @Test
    fun `the sdk switch wins over an endpoint`() {
        val tracing =
            MatchTracing.fromEnv(
                mapOf(
                    "OTEL_EXPORTER_OTLP_ENDPOINT" to "http://127.0.0.1:4318",
                    "OTEL_SDK_DISABLED" to "true",
                )
            )

        assertSame(MatchTracing.NOOP, tracing)
    }

    @Test
    fun `an endpoint turns exporting on`() {
        val tracing =
            MatchTracing.fromEnv(
                mapOf(
                    "OTEL_EXPORTER_OTLP_ENDPOINT" to "http://127.0.0.1:4318/",
                    "OTEL_SERVICE_NAME" to "survivalgames",
                    "OTEL_RESOURCE_ATTRIBUTES" to "deployment.environment=stage,k8s.pod.name=a%2Cb",
                )
            )

        assertTrue(tracing.enabled)
        assertTrue(tracing.tracer.spanBuilder("match duel").startSpan().isRecording)
        tracing.close()
    }
}
