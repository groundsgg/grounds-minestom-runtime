package gg.grounds.runtime.match

import java.util.UUID

/**
 * What service-match sends alongside a StartMatch as NATS headers, outside the proto.
 *
 * Every field is optional: a service-match that predates them sends none, and a value that does not
 * parse is dropped rather than refusing the match — a timeline is never worth a lost round.
 *
 * @property traceparent the match trace's W3C context, continued by this server's spans
 * @property enqueuedAt each roster player's ticket enqueue time, epoch millis
 * @property formedAt when the matchmaker formed the match, epoch millis
 */
internal data class PushHeaders(
    val traceparent: String? = null,
    val enqueuedAt: Map<UUID, Long> = emptyMap(),
    val formedAt: Long? = null,
) {
    companion object {
        const val TRACEPARENT = "traceparent"
        const val ENQUEUED_AT = "grounds-enqueued-at"
        const val FORMED_AT = "grounds-formed-at"

        val NONE = PushHeaders()

        fun parse(traceparent: String?, enqueuedAt: String?, formedAt: String?): PushHeaders =
            PushHeaders(
                traceparent = traceparent?.trim()?.takeIf { it.isNotEmpty() },
                enqueuedAt = parseEnqueuedAt(enqueuedAt),
                formedAt = formedAt?.trim()?.toLongOrNull()?.takeIf { it > 0 },
            )

        /** `<uuid>=<epochMillis>,...`; each malformed entry is skipped on its own. */
        private fun parseEnqueuedAt(value: String?): Map<UUID, Long> {
            if (value.isNullOrBlank()) return emptyMap()
            return value
                .split(',')
                .mapNotNull { entry ->
                    val parts = entry.split('=')
                    if (parts.size != 2) return@mapNotNull null
                    val player =
                        try {
                            UUID.fromString(parts[0].trim())
                        } catch (e: IllegalArgumentException) {
                            return@mapNotNull null
                        }
                    val at = parts[1].trim().toLongOrNull()?.takeIf { it > 0 }
                    at?.let { player to it }
                }
                .toMap()
        }
    }
}
