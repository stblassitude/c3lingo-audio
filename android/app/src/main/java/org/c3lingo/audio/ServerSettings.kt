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
 * Defaults to the production site. A custom URL for testing can be entered in the app's
 * settings (also reachable from the system's App info screen), or passed when launching
 * the app as the `serverURL` extra, which applies to this run only.
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
        get() = serverURL.value == DEFAULT_URL

    /** Uses this URL instead of the stored one until the app is restarted, e.g. for testing. */
    fun setOverride(url: String?) {
        override = url
        refresh()
    }

    private fun refresh() {
        _serverURL.value = current()
    }

    private fun current(): Uri =
        urlFrom(override) ?: urlFrom(preferences.getString(KEY, null)) ?: DEFAULT_URL

    companion object {
        val DEFAULT_URL: Uri = "https://audio.c3lingo.org/".toUri()
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
    }
}
