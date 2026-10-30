package org.c3lingo.audio

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The frontend server the app talks to.
 *
 * A custom URL for testing can be entered in the app's server dialog or its settings
 * (reachable from the system's App info screen). Without one, the app uses the default:
 * the `serverURL` extra the app was launched with, for that run, if given, otherwise the
 * production site.
 */
class ServerSettings(context: Context) {
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private var override: String? = null

    private val _serverURL = MutableStateFlow(current())

    /** The URL currently in effect. */
    val serverURL: StateFlow<Uri> = _serverURL.asStateFlow()

    /** The custom URL as stored; empty if the default is used. */
    var customURLString: String
        get() = preferences.getString(KEY, null).orEmpty()
        set(value) {
            preferences.edit { putString(KEY, value.trim()) }
            refresh()
        }

    val isUsingDefault: Boolean
        get() = serverURL.value == defaultURL

    /** The server used when no custom URL is stored. */
    val defaultURL: Uri
        get() = urlFrom(override) ?: BUILT_IN_URL

    /** Uses this URL as the default instead of the production site until the app is restarted, e.g. for testing. */
    fun setOverride(url: String?) {
        override = url
        refresh()
    }

    private fun refresh() {
        _serverURL.value = current()
    }

    private fun current(): Uri =
        urlFrom(preferences.getString(KEY, null)) ?: defaultURL

    companion object {
        val BUILT_IN_URL: Uri = "https://audio.c3lingo.org/".toUri()
        /** Linked from the server settings; the stores also require it to be in the app. */
        const val PRIVACY_POLICY_URL = "https://github.com/stblassitude/c3lingo-audio/blob/main/PRIVACY.md"
        /** The source of the app and of the server side, linked from the server settings: anyone can run a server. */
        const val APP_SOURCE_URL = "https://github.com/stblassitude/c3lingo-audio"
        const val FRONTEND_SOURCE_URL = "https://codeberg.org/c3lingo/listen-frontend"
        const val GALENE_SOURCE_URL = "https://github.com/jech/galene"
        private const val KEY = "serverURL"

        /**
         * Parses user input into a usable server URL, or returns null for empty or invalid input.
         * A missing scheme defaults to https, and a trailing slash is added so relative URLs work.
         */
        fun urlFrom(input: String?): Uri? {
            var string = input?.trim().orEmpty()
            if (string.isEmpty()) return null
            if (!string.contains("://")) string = "https://$string"
            val uri = runCatching { URI(string) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()
            if (scheme != "https" && scheme != "http") return null
            if (uri.host.isNullOrEmpty()) return null
            val path = uri.rawPath.ifEmpty { "/" }
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            return "$scheme://${uri.rawAuthority}$path$query".toUri()
        }

        /**
         * How a server URL is shown for editing: without `https://` and without the slash
         * of an empty path, e.g. `audio.c3lingo.org` or `http://10.0.2.2:8000`.
         */
        fun displayString(url: Uri): String {
            var string = url.toString()
            if (url.scheme == "https") string = string.substringAfter("://")
            if (url.path == "/" && url.query == null) string = string.removeSuffix("/")
            return string
        }

        /**
         * Whether the host looks finished, e.g. `example.org`, `localhost` or an IP address,
         * rather than still being typed, e.g. `exa`.
         */
        fun looksComplete(url: Uri): Boolean {
            val host = url.host?.lowercase() ?: return false
            if (host == "localhost" || host.contains(':') || host.startsWith('[')) return true
            val labels = host.split('.')
            return labels.size >= 2 && labels.all { it.isNotEmpty() } && labels.last().length >= 2
        }
    }
}
