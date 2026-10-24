package org.c3lingo.audio

import android.net.Uri
import androidx.core.net.toUri
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** An audio channel, i.e. a Galène group, as listed in the server's `public-groups.json`. */
data class Channel(
    /** The Galène group name, e.g. `Saal_1_0_Floor` or `test`. */
    val name: String,
    val displayName: String = name,
    /** The Galène WebSocket endpoint, e.g. `wss://example.org/ws`. */
    val endpoint: Uri? = null,
    /** The group URL, e.g. `https://example.org/group/test/`. */
    val location: Uri? = null,
) {
    /** Returns the WebSocket endpoint, asking the server for the group status if the frontend didn't provide it. */
    suspend fun resolveEndpoint(server: Uri, http: OkHttpClient): Uri {
        endpoint?.let { return it }
        fun directory(url: String) = if (url.endsWith("/")) url else "$url/"
        val group = directory(location?.toString() ?: "${directory(server.toString())}group/${Uri.encode(name)}/")
        val body = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(group + ".status").build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${response.request.url}")
                response.body.string()
            }
        }
        val status = JSONObject(body).getString("endpoint")
        return resolve(group.toUri(), status) ?: throw IOException("invalid endpoint $status")
    }

    companion object {
        /** Creates a channel from the JSON object the web frontend passes to the bridge. */
        fun fromJson(json: JSONObject, base: Uri?): Channel? {
            val name = json.string("name") ?: return null
            return Channel(
                name = name,
                displayName = json.string("displayName") ?: name,
                endpoint = json.string("endpoint")?.let { resolve(base, it) },
                location = json.string("location")?.let { resolve(base, it) },
            )
        }

        private fun resolve(base: Uri?, reference: String): Uri? = runCatching {
            val uri = if (base != null) URI(base.toString()).resolve(reference) else URI(reference)
            if (uri.isAbsolute) uri.toString().toUri() else null
        }.getOrNull()
    }
}

/** The string value for [key], or null if it is missing, not a string, or empty. */
internal fun JSONObject.string(key: String): String? = (opt(key) as? String)?.takeIf { it.isNotEmpty() }

/**
 * What the frontend knows about a channel beyond its name: the hall, the language being
 * interpreted into and the current talk, from the bridge's optional `describe` message.
 */
data class ChannelDescription(
    /** The hall's short name, e.g. `One`. */
    val room: String? = null,
    val language: Language? = null,
    val talk: Talk? = null,
) {
    data class Language(val name: String, val flag: String?)

    data class Talk(
        val title: String,
        /** The talk's page in the schedule, only if it is an `http` or `https` URL. */
        val url: Uri?,
        /** The start time as the schedule gives it, e.g. `14:30`. */
        val start: String,
        val started: Boolean,
        /** The schedule day, if the talk isn't today. */
        val day: Int?,
    )

    /** The hall and language, e.g. "One · 🇬🇧 English", or null if the language isn't known. */
    val title: String?
        get() {
            val language = language ?: return null
            val name = listOfNotNull(language.flag, language.name).joinToString(" ")
            return listOfNotNull(room, name).joinToString(" · ")
        }

    companion object {
        fun fromJson(json: JSONObject): ChannelDescription {
            val language = json.optJSONObject("language")
            val talk = json.optJSONObject("talk")
            val title = talk?.string("title")
            val start = talk?.string("start")
            return ChannelDescription(
                room = json.string("room"),
                language = language?.string("name")?.let { Language(it, language.string("flag")) },
                talk = if (title != null && start != null) {
                    val url = talk.string("url")?.toUri()?.takeIf { it.scheme?.lowercase() in setOf("http", "https") }
                    Talk(title, url, start, talk.opt("started") as? Boolean ?: false, (talk.opt("day") as? Number)?.toInt())
                } else {
                    null
                },
            )
        }

        /** Parses the `channels` object of a `describe` message, keyed by channel name. */
        fun all(json: JSONObject?): Map<String, ChannelDescription> {
            json ?: return emptyMap()
            return json.keys().asSequence()
                .mapNotNull { name -> json.optJSONObject(name)?.let { name to fromJson(it) } }
                .toMap()
        }
    }
}
