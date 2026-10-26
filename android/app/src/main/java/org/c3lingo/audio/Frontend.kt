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
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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
import kotlin.math.roundToInt
import kotlin.math.max
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.size
import android.widget.AbsoluteLayout
import android.graphics.Canvas

/**
 * Hosts the web frontend loaded from the server, and connects it to the native player
 * through `FrontendBridge.js` (shared with the Apple app), which implements version 2 of
 * the frontend's native bridge protocol (`docs/playback-api.md` in listen-frontend).
 *
 * The player's state is the only source of truth: commands from the page go to the
 * player, and every state change, whatever caused it, is reported back in full.
 */
class Frontend(private val context: Context, private val player: Player, scope: CoroutineScope) {
    /**
     * Holds the web view, which is replaced when the server changes. The pull to refresh
     * container only ever lays out the child it saw first, so it gets this one, which stays.
     */
    private val webViewHost = FrameLayout(context)

    /** The web view inside a pull to refresh container; the container stays when the web view is replaced. */
    val view = SwipeRefreshLayout(context).apply {
        setOnRefreshListener { reload() }
        addView(webViewHost)
        setOnChildScrollUpCallback { _, _ -> webView.canScrollVertically(-1) }
    }

    private val _loadError = MutableStateFlow<String?>(null)
    /** Why the page couldn't be loaded, in technical terms, shown below the explanation. */
    val loadError: StateFlow<String?> = _loadError.asStateFlow()
    private val _hasLoaded = MutableStateFlow(false)
    val hasLoaded: StateFlow<Boolean> = _hasLoaded.asStateFlow()
    private val _isShowingServerDialog = MutableStateFlow(false)
    /** The server dialog, opened from the settings button on the page or the error screen. */
    val isShowingServerDialog: StateFlow<Boolean> = _isShowingServerDialog.asStateFlow()

    /** For testing: selects this channel once the page has loaded. */
    var autoplay: String? = null

    private var webView = createWebView()
    private var url: Uri? = null
    private var scriptHandler: ScriptHandler? = null
    private var listenerInstalled = false
    private var navigationFailed = false
    /**
     * Set for the pages the app loads itself, which must be a c3lingo frontend; pages
     * the user navigates to from there aren't checked.
     */
    private var verifyPage = false
    /** The HTTP error the page being loaded returned, if any. */
    private var httpError: String? = null
    /** The bridge, told the app's UI language so the page uses the same one. */
    private val bridgeScript = "window.__c3lingoNativeConfig = " +
        JSONObject().put("language", context.getString(R.string.language_tag)) + ";\n" +
        context.assets.open("FrontendBridge.js").bufferedReader().use { it.readText() }

    init {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webViewHost.addView(webView)
        scope.launch { player.state.collect(::sendState) }
    }

    fun load(url: Uri) {
        if (url == this.url) return
        Log.i(TAG, "loading $url")
        this.url = url
        _loadError.value = null
        _hasLoaded.value = false
        player.describe(emptyMap())
        installBridge(url)
        verifyPage = true
        httpError = null
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
        verifyPage = true
        httpError = null
        if (_hasLoaded.value) {
            webView.reload()
        } else {
            url?.let { webView.loadUrl(it.toString()) }
        }
    }

    fun showServerDialog(show: Boolean) {
        _isShowingServerDialog.value = show
    }

    fun destroy() {
        view.removeAllViews()
        webViewHost.removeAllViews()
        webView.destroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() = FrontendWebView(context) { showServerDialog(true) }.apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // The page scrolls but can't be zoomed, neither by pinching nor by double tapping.
        settings.setSupportZoom(false)
        webViewClient = Client()
        webChromeClient = WebChromeClient()
    }

    /** Replaces the web view with a new one; the bridge is installed again on the next load. */
    private fun replaceWebView() {
        webViewHost.removeView(webView)
        webView.destroy()
        scriptHandler = null
        listenerInstalled = false
        webView = createWebView()
        webViewHost.addView(webView)
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
            "describe" -> player.describe(ChannelDescription.all(message.optJSONObject("channels")))
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
            if (!verifyPage) {
                pageLoaded(url)
                return
            }
            verifyPage = false
            httpError?.let {
                Log.e(TAG, "$url returned $it")
                notLoaded(it)
                return
            }
            // The splash screen stays until then, so a page that isn't a frontend never shows.
            view.evaluateJavascript(IS_FRONTEND_SCRIPT) { result ->
                if (view !== webView) return@evaluateJavascript
                if (result == "true") {
                    pageLoaded(url)
                } else {
                    Log.e(TAG, "$url is not a c3lingo frontend")
                    notLoaded(context.getString(R.string.load_error_not_compatible))
                }
            }
        }

        private fun notLoaded(reason: String) {
            _hasLoaded.value = false
            _loadError.value = reason
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (!request.isForMainFrame) return
            httpError = listOf("HTTP ${errorResponse.statusCode}", errorResponse.reasonPhrase)
                .filterNot { it.isNullOrBlank() }.joinToString(" ")
        }

        private fun pageLoaded(url: String?) {
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
        /**
         * Whether the page is a c3lingo frontend: they all declare the native bridge
         * version, whether or not they support the one the app speaks.
         */
        const val IS_FRONTEND_SCRIPT = "document.querySelector('meta[name=\"c3lingo-native-bridge\"]') !== null"
        /** Seen by the page as `window.c3lingoAndroid`, see FrontendBridge.js. */
        const val MESSAGE_LISTENER = "c3lingoAndroid"
    }
}

/**
 * The web view with a small settings button in the bottom right corner of the page, over
 * the page and scrolling with it. On a page shorter than the view, it sits in the bottom
 * right corner of the view.
 */
@SuppressLint("ViewConstructor")
@Suppress("DEPRECATION") // A web view places its children with AbsoluteLayout, in page coordinates.
private class FrontendWebView(context: Context, onSettings: () -> Unit) : WebView(context) {
    private val buttonSize = (48 * resources.displayMetrics.density).roundToInt()
    private val margin = (8 * resources.displayMetrics.density).roundToInt()
    private val settingsButton = ComposeView(context).apply {
        setContent {
            C3lingoTheme {
                FilledTonalIconButton(
                    onClick = onSettings,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(Icons.Filled.Settings, stringResource(R.string.settings_title), Modifier.size(20.dp))
                }
            }
        }
    }

    init {
        addView(settingsButton, AbsoluteLayout.LayoutParams(buttonSize, buttonSize, 0, 0))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // The page's height changes as it renders, and there is no callback for that.
        val params = settingsButton.layoutParams as AbsoluteLayout.LayoutParams
        val x = width - buttonSize - margin
        val y = max(computeVerticalScrollRange(), height) - buttonSize - margin
        if (params.x != x || params.y != y) {
            params.x = x
            params.y = y
            post { settingsButton.requestLayout() }
        }
    }
}
