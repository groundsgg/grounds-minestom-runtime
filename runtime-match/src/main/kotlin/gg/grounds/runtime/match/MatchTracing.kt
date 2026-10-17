package gg.grounds.runtime.match

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * The tracer the match timeline writes its spans with.
 *
 * Configured only by the standard OpenTelemetry environment, and only the part of it this needs —
 * not the autoconfigure module, which would also start metric and log exporters this server has no
 * use for:
 * - `OTEL_EXPORTER_OTLP_ENDPOINT` — the collector's OTLP/HTTP base URL; spans go to `/v1/traces`.
 *   **Unset means no tracing at all**: a no-op tracer, no exporter, no thread.
 * - `OTEL_SERVICE_NAME` — `service.name`, overriding one in the attributes.
 * - `OTEL_RESOURCE_ATTRIBUTES` — `key=value,...`, percent-encoded values.
 * - `OTEL_SDK_DISABLED=true` — off, whatever else is set.
 */
internal class MatchTracing
private constructor(val tracer: Tracer, private val provider: SdkTracerProvider?) : AutoCloseable {

    val enabled: Boolean
        get() = provider != null

    /** Flushes the spans still queued; bounded, because this runs while the pod is going away. */
    override fun close() {
        provider?.shutdown()?.join(5, TimeUnit.SECONDS)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(MatchTracing::class.java)
        private const val INSTRUMENTATION = "gg.grounds.runtime.match"
        private val SERVICE_NAME = AttributeKey.stringKey("service.name")

        val NOOP = MatchTracing(OpenTelemetry.noop().getTracer(INSTRUMENTATION), null)

        /** A tracer over [provider], for tests that export to memory. */
        fun of(provider: SdkTracerProvider) = MatchTracing(provider.get(INSTRUMENTATION), provider)

        fun fromEnv(env: Map<String, String> = System.getenv()): MatchTracing {
            val endpoint = env["OTEL_EXPORTER_OTLP_ENDPOINT"]?.trim().orEmpty()
            if (endpoint.isEmpty() || env["OTEL_SDK_DISABLED"].equals("true", ignoreCase = true))
                return NOOP
            return try {
                val exporter =
                    OtlpHttpSpanExporter.builder()
                        .setEndpoint(endpoint.trimEnd('/') + "/v1/traces")
                        .build()
                val provider =
                    SdkTracerProvider.builder()
                        .setResource(Resource.getDefault().merge(Resource.create(attributes(env))))
                        .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                        .build()
                logger.info("Match tracing exports to {}", endpoint)
                of(provider)
            } catch (e: Exception) {
                // Tracing is diagnostics; a server that will not boot over it is an outage.
                logger.error("Match tracing disabled: cannot export to {}", endpoint, e)
                NOOP
            }
        }

        private fun attributes(env: Map<String, String>): Attributes {
            val builder = Attributes.builder()
            env["OTEL_RESOURCE_ATTRIBUTES"]
                ?.split(',')
                ?.mapNotNull { entry ->
                    val separator = entry.indexOf('=')
                    if (separator <= 0) return@mapNotNull null
                    entry.substring(0, separator).trim() to
                        URLDecoder.decode(
                            entry.substring(separator + 1).trim(),
                            StandardCharsets.UTF_8,
                        )
                }
                ?.forEach { (key, value) -> builder.put(key, value) }
            env["OTEL_SERVICE_NAME"]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { builder.put(SERVICE_NAME, it) }
            return builder.build()
        }
    }
}
