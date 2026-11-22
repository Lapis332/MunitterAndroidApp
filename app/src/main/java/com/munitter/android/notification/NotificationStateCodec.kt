package com.munitter.android.notification

import org.json.JSONArray
import org.json.JSONObject

internal object NotificationStateCodec {
    fun decode(raw: String?, legacyIds: String?): NotificationState = runCatching {
        if (raw != null) {
            val root = JSONObject(raw)
            val sources = root.getJSONObject("sourcesById")
            val records = buildMap {
                for (id in sources.keys()) {
                    val values = sources.getJSONArray(id)
                    put(id, (0 until values.length()).map { values.getString(it) }.toSet())
                }
            }
            val readIds = root.optJSONArray("readSources") ?: JSONArray()
            NotificationState(
                records,
                root.optInt("unreadCount", -1).takeIf { it >= 0 },
                (0 until readIds.length()).map { readIds.getString(it) }.toSet(),
            )
        } else {
            val ids = JSONArray(legacyIds ?: "[]")
            NotificationState(buildMap {
                for (index in 0 until ids.length()) {
                    val id = ids.optString(index).takeIf(String::isNotBlank) ?: continue
                    // Preserve the original native ID so its existing card can
                    // be canceled, but compare its canonical source identity.
                    put(id, setOf(NotificationIdentity.canonical(id)))
                }
            })
        }
    }.getOrDefault(NotificationState())

    fun encode(state: NotificationState): String {
        val sources = JSONObject()
        state.sourcesById.toSortedMap().forEach { (id, values) ->
            sources.put(id, JSONArray(values.sorted()))
        }
        val root = JSONObject().put("sourcesById", sources)
            .put("readSources", JSONArray(state.readSources.toList()))
        state.unreadCount?.let { root.put("unreadCount", it) }
        return root.toString()
    }
}
