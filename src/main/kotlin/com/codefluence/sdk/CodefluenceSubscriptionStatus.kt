package com.codefluence.sdk

import org.json.JSONObject

/**
 * Read-only subscription status (C3). This is a normalized **view** of adapter
 * events, not an entitlement decision. Do not gate paid features on it; use your
 * payment provider's entitlement/receipt check for access control.
 */
data class CodefluenceSubscriptionStatus(
    val state: State,
    val sandbox: Boolean,
    val updatedAt: String?,
    val events: List<Event>
) {
    enum class State(val raw: String) {
        NONE("none"), ACTIVE("active"), GRACE("grace"),
        CANCELLED("cancelled"), EXPIRED("expired"), REFUNDED("refunded");

        companion object {
            fun fromRaw(value: String?): State? = entries.firstOrNull { it.raw == value }
        }
    }

    data class Event(
        /** Known event kind, or null for a forward-compatible unknown type. */
        val kind: Kind?,
        /** Raw server type string, preserved even when [kind] is unknown. */
        val rawType: String,
        val occurredAt: String,
        val source: String
    ) {
        enum class Kind(val raw: String) {
            STARTED("started"), RENEWED("renewed"), CANCELLED("cancelled"),
            REFUNDED("refunded"), GRACE("grace"), EXPIRED("expired");

            companion object {
                fun fromRaw(value: String): Kind? = entries.firstOrNull { it.raw == value }
            }
        }
    }

    companion object {
        /** Parses the `/track/subscription` 200 body; null when malformed. */
        fun parse(json: JSONObject): CodefluenceSubscriptionStatus? {
            val state = State.fromRaw(json.optString("state").takeIf { it.isNotBlank() }) ?: return null
            val sandbox = json.optBoolean("sandbox", false)
            val updatedAt = if (json.isNull("updatedAt")) null else json.optString("updatedAt").takeIf { it.isNotBlank() }
            val events = mutableListOf<Event>()
            val rawEvents = json.optJSONArray("events")
            if (rawEvents != null) {
                for (i in 0 until rawEvents.length()) {
                    val entry = rawEvents.optJSONObject(i) ?: continue
                    val type = entry.optString("type").takeIf { it.isNotBlank() } ?: continue
                    val occurredAt = entry.optString("occurredAt").takeIf { it.isNotBlank() } ?: continue
                    val source = entry.optString("source").takeIf { it.isNotBlank() } ?: continue
                    events.add(Event(Event.Kind.fromRaw(type), type, occurredAt, source))
                }
            }
            return CodefluenceSubscriptionStatus(state, sandbox, updatedAt, events)
        }
    }
}
