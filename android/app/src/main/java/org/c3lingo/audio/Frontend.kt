package org.c3lingo.audio

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Hosts the web frontend loaded from the server, and connects it to the native player
 * through `FrontendBridge.js` (shared with the Apple app), which implements version 2 of
 * the frontend's native bridge protocol (`docs/playback-api.md` in listen-frontend).
 *
 * The player's state is the only source of truth: commands from the page go to the
 * player, and every state change, whatever caused it, is reported back in full.
 */
class Frontend(private val context: Context, private val player: Player, scope: CoroutineScope) {
    /** The web view inside a pull to refresh container; the container stays when the web view is replaced. */
    val view = SwipeRefreshLayout(context).apply {
        setOnRefreshListener { reload() }
    }

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()
    private val _hasLoaded = MutableStateFlow(false)
    val hasLoaded: StateFlow<Boolean> = _hasLoaded.asStateFlow()
    private val _descriptions = MutableStateFlow<Map<String, ChannelDescription>>(emptyMap())
    /** Hall, language and talk per channel name, if the page sends them. */
    val descriptions: StateFlow<Map<String, ChannelDescription>> = _descriptions.asStateFlow()

    /** For testing: selects this channel once the page has loaded. */
    var autoplay: String? = null

    private var webView = createWebView()
    private var url: Uri? = null
    private var scriptHandler: ScriptHandler? = null
    private var listenerInstalled = false
    private var navigationFailed = false
    /** The bridge, told the app's UI language so the page uses the same one. */
    private val bridgeScript = "window.__c3lingoNativeConfig = " +
        JSONObject().put("language", context.getString(R.string.language_tag)) + ";\n" +
        context.assets.open("FrontendBridge.js").bufferedReader().use { it.readText() }

    init {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        view.addView(webView)
        scope.launch { player.state.collect(::sendState) }
    }

    fun load(url: Uri) {
        if (url == this.url) return
        Log.i(TAG, "loading $url")
        this.url = url
        _loadError.value = null
        _hasLoaded.value = false
        _descriptions.value = emptyMap()
        installBridge(url)
        webView.loadUrl(url.toString())
    }

    /**
     * Starts over with [url] in a new web view, without anything left from before: no
     * page state, history, cache, storage or cookies. Used when the server changes.
     */
    fun restart(url: Uri) {
        Log.i(TAG, "starting over with $url")
        webView.stopLoading()
        webView.clearCache(true)
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
        replaceWebView()
        this.url = null
        load(url)
    }

    fun reload() {
        _loadError.value = null
        if (_hasLoaded.value) {
            webView.reload()
        } else {
            url?.let { webView.loadUrl(it.toString()) }
        }
    }

    fun destroy() {
        view.removeAllViews()
        webView.destroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() = WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        webViewClient = Client()
        webChromeClient = WebChromeClient()
    }

    /** Replaces the web view with a new one; the bridge is installed again on the next load. */
    private fun replaceWebView() {
        view.removeView(webView)
        webView.destroy()
        scriptHandler = null
        listenerInstalled = false
        webView = createWebView()
        view.addView(webView)
    }

    // Bridge

    /** The bridge is only offered to the frontend's own origin. */
    private fun installBridge(url: Uri) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Log.e(TAG, "this WebView doesn't support message listeners, the page will play audio itself")
            return
        }
        val port = if (url.port != -1) ":${url.port}" else ""
        val origins = setOf("${url.scheme}://${url.host}$port")
        scriptHandler?.remove()
        scriptHandler = null
        if (listenerInstalled) WebViewCompat.removeWebMessageListener(webView, MESSAGE_LISTENER)
        WebViewCompat.addWebMessageListener(webView, MESSAGE_LISTENER, origins) { _, message, _, isMainFrame, _ ->
            if (isMainFrame) message.data?.let(::receive)
        }
        listenerInstalled = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            scriptHandler = WebViewCompat.addDocumentStartJavaScript(webView, bridgeScript, origins)
        }
    }

    private fun sendState(state: Player.State) {
        val protocolState = when (state.status) {
            Player.Status.IDLE, Player.Status.PAUSED -> "stopped"
            Player.Status.CONNECTING, Player.Status.RECONNECTING -> "connecting"
            Player.Status.WAITING -> "waiting"
            Player.Status.PLAYING -> "playing"
        }
        val detail = JSONObject()
            .put("state", protocolState)
            // A paused channel stays selected, which the protocol allows while stopped.
            .put("channel", state.channel?.name ?: JSONObject.NULL)
            .put("muted", state.isMuted)
            .put("error", state.lastError ?: JSONObject.NULL)
        evaluate("window.__c3lingoNativeState && window.__c3lingoNativeState($detail)")
    }

    private fun evaluate(script: String) {
        webView.evaluateJavascript(script, null)
    }

    private fun receive(text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        val command = message.string("cmd") ?: return
        Log.d(TAG, "bridge command $command")
        when (command) {
            // Also brings a reloaded page up to date while audio keeps playing.
            "ready" -> sendState(player.state.value)
            "play" -> {
                val channel = message.optJSONObject("channel")?.let { Channel.fromJson(it, webView.url?.toUri()) }
                if (channel == null) {
                    Log.e(TAG, "invalid channel in play message: ${message.opt("channel")}")
                    return
                }
                player.play(channel)
            }
            "stop" -> player.stop()
            "setMuted" -> {
                val muted = message.opt("muted") as? Boolean ?: return
                if (player.state.value.isMuted != muted) player.setMuted(muted)
            }
            // Only information for display; it doesn't change the player's state.
            "describe" -> _descriptions.value = ChannelDescription.all(message.optJSONObject("channels"))
            else -> Log.w(TAG, "unknown bridge command $command")
        }
    }

    private fun isFrontend(url: Uri): Boolean {
        val base = this.url ?: return false
        return url.host == base.host && url.port == base.port && url.scheme == base.scheme
    }

    private fun openExternally(url: Uri) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no app to open $url")
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Links leaving the frontend, e.g. to the schedule, open in the browser.
            val url = request.url
            if (request.isForMainFrame && request.hasGesture() && !isFrontend(url)) {
                openExternally(url)
                return true
            }
            return false
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            navigationFailed = false
            // Without document start scripts, this is the earliest the bridge can be added.
            if (scriptHandler == null && url != null && isFrontend(url.toUri())) {
                evaluate(bridgeScript)
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            this@Frontend.view.isRefreshing = false
            if (navigationFailed) return
            Log.i(TAG, "loaded $url")
            _hasLoaded.value = true
            _loadError.value = null
            autoplay?.takeIf { it.isNotEmpty() }?.let { channel ->
                autoplay = null
                evaluate("setTimeout(() => { location.hash = ${JSONObject.quote(channel)}; }, 500)")
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            Log.e(TAG, "loading failed: ${error.errorCode} ${error.description}")
            navigationFailed = true
            this@Frontend.view.isRefreshing = false
            _loadError.value = error.description.toString()
        }

        // Before Android 8, the app is killed along with the web content process.
        @RequiresApi(Build.VERSION_CODES.O)
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.e(TAG, "web content process gone (crashed: ${detail.didCrash()}), reloading")
            // The web view can't be used any more; replace it.
            replaceWebView()
            url?.let {
                this@Frontend.url = null
                load(it)
            }
            return true
        }
    }

    private companion object {
        const val TAG = "frontend"
        /** Seen by the page as `window.c3lingoAndroid`, see FrontendBridge.js. */
        const val MESSAGE_LISTENER = "c3lingoAndroid"
    }
}
