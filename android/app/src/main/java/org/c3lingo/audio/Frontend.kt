package org.c3lingo.audio

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.math.max
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

    /**
     * Where the bottom of the page is, in pixels from the top of the web view, for the
     * settings button that sits there, over the page and scrolling with it; on a page
     * shorter than the view, at the bottom of the view. The button is drawn by Compose
     * beside the web view rather than in it: a web view reports its page to accessibility
     * services itself, which leaves out views added to it, and keyboard focus couldn't
     * get from there to the player bar.
     */
    val pageBottom = mutableIntStateOf(0)

    /** Whether the splash or error screen covers the page, see [setCovered]. */
    val isCovered = mutableStateOf(true)

    /**
     * Moves keyboard focus from the page to the native controls after it (`forward`, Tab
     * past the page's last element) or before it (Shift+Tab before its first); false if
     * there are none, and the focus wraps around within the page. Without this, the focus
     * would never leave the page: Compose hands it back to its first focusable element,
     * which is the web view.
     */
    var onFocusLeavesPage: (forward: Boolean) -> Boolean = { false }

    /** Whether the settings button is there to take keyboard focus. */
    val isSettingsButtonFocusable: Boolean
        get() = !isCovered.value

    /** Scrolls to the end of the page, where the settings button is, once it has keyboard focus. */
    fun revealSettingsButton() {
        webView.pageDown(true)
    }

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
    private val _help = MutableStateFlow<Help?>(null)
    /** The page's help text, if it sends one. */
    val help: StateFlow<Help?> = _help.asStateFlow()
    private val _isShowingHelp = MutableStateFlow(false)
    /** The help, opened from the page's help button, and by itself on the first launch. */
    val isShowingHelp: StateFlow<Boolean> = _isShowingHelp.asStateFlow()
    /** Whether the help has been shown by itself, see [showHelpForFirstTime]. */
    var helpShown: Boolean
        get() = preferences.getBoolean("helpShown", false)
        set(value) = preferences.edit().putBoolean("helpShown", value).apply()
    private val preferences = context.getSharedPreferences("help", Context.MODE_PRIVATE)

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
    /** Whether the page has called `ready()` since it was last loaded. */
    private var isPageReady = false
    /** A link that opened the app, kept until the page can take it, see [open]. */
    private var pendingLink: AppLink? = null
    /** Whether the page is paused because nobody can see it, see [pause]. */
    private var isPaused = false
    /** The web content process went away while paused; the page is loaded again on [resume]. */
    private var needsReload = false
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
        _help.value = null
        isPageReady = false
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
        isPageReady = false
        verifyPage = true
        httpError = null
        if (_hasLoaded.value) {
            webView.reload()
        } else {
            url?.let { webView.loadUrl(it.toString()) }
        }
    }

    /**
     * Hides the page from accessibility services while the splash or error screen covers
     * it, so they can't reach a page that hasn't passed the check either.
     */
    fun setCovered(covered: Boolean) {
        isCovered.value = covered
        view.importantForAccessibility =
            if (covered) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    /** Whether TalkBack or another service that explores the screen by touch is on. */
    val isScreenReaderOn: Boolean
        get() = context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true

    /**
     * Moves input and accessibility focus back into the page, after a native control that
     * had it has gone away: to the channel the player bar's Stop button stopped, or to the
     * page's help button once the help has been closed. Waits for the player bar to have
     * gone. Pages that don't handle the `c3lingo:focus` event keep the focus at their start.
     */
    fun focusPage(channel: String) = focusPage(JSONObject().put("channel", channel))

    fun focusPageAfterHelp() = focusPage(JSONObject().put("help", true))

    private fun focusPage(detail: JSONObject) {
        webViewHost.postDelayed({
            webView.requestFocus()
            evaluate("window.__c3lingoNativeFocus && window.__c3lingoNativeFocus($detail)")
        }, FOCUS_DELAY_MILLIS)
    }

    fun showServerDialog(show: Boolean) {
        _isShowingServerDialog.value = show
    }

    fun showHelp(show: Boolean) {
        _isShowingHelp.value = show && _help.value != null
    }

    /** Shows the help by itself once, the first time the app has it, after the page has loaded. */
    private fun showHelpForFirstTime() {
        if (_help.value == null || !_hasLoaded.value || _isShowingServerDialog.value || helpShown) return
        helpShown = true
        showHelp(true)
    }

    /**
     * Pauses the page while the app isn't visible, e.g. in the background or with the
     * screen locked: playback doesn't need it, so its timers, network requests and
     * rendering stop, and it isn't sent the player's state. The page catches up on its
     * own when it is visible again; [resume] sends it the current state.
     */
    fun pause() {
        if (isPaused) return
        Log.i(TAG, "pausing the page")
        isPaused = true
        webView.onPause()
        // Pauses all web views in the app; this is the only one.
        webView.pauseTimers()
    }

    fun resume() {
        if (!isPaused) return
        Log.i(TAG, "resuming the page")
        isPaused = false
        webView.resumeTimers()
        webView.onResume()
        if (needsReload) {
            needsReload = false
            url?.let {
                this.url = null
                load(it)
            }
        } else {
            sendState(player.state.value)
        }
    }

    fun destroy() {
        // Timers are paused for the whole app, which would include the next activity's web view.
        if (isPaused) webView.resumeTimers()
        view.removeAllViews()
        webViewHost.removeAllViews()
        webView.destroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() = FrontendWebView(context, { pageBottom.intValue = it }, { onFocusLeavesPage(it) }).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // The page scrolls but can't be zoomed, neither by pinching nor by double tapping.
        settings.setSupportZoom(false)
        // Larger text still reaches it: it follows the system font size, up to twice the
        // size, where the page still has room for its layout. The activity is recreated
        // when the font size changes, and with it the web view.
        settings.textZoom = (context.resources.configuration.fontScale * 100).roundToInt().coerceIn(100, 200)
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
        if (isPaused) webView.onPause()
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

    /**
     * Opens a link to the app, e.g. `https://audio.c3lingo.org/#Saal_1_0_Floor`: plays the
     * channel it names, and tells the page which channel, hall or talk it points to.
     */
    fun open(url: Uri) {
        val link = AppLink.fromUri(url) ?: return
        Log.i(TAG, "opening $url")
        pendingLink = link
        openPendingLink()
    }

    /**
     * Hands the pending link to the page once the page is ready and has described its
     * channels, which tells a channel's name from a hall's.
     */
    private fun openPendingLink() {
        val link = pendingLink ?: return
        val descriptions = player.descriptions.value
        if (!isPageReady || descriptions.isEmpty()) return
        pendingLink = null
        val detail = when (link) {
            is AppLink.Talk -> JSONObject().put("talk", link.guid)
            is AppLink.Name -> if (link.name in descriptions) {
                val state = player.state.value
                if (state.channel?.name != link.name || state.status == Player.Status.PAUSED) player.play(Channel(link.name))
                JSONObject().put("channel", link.name)
            } else {
                JSONObject().put("hall", link.name)
            }
        }
        evaluate("window.__c3lingoNativeLink && window.__c3lingoNativeLink($detail)")
    }

    private fun sendState(state: Player.State) {
        if (isPaused) return
        val protocolState = when (state.status) {
            Player.Status.IDLE, Player.Status.PAUSED -> "stopped"
            Player.Status.CONNECTING, Player.Status.RECONNECTING -> "connecting"
            Player.Status.WAITING -> "waiting"
            // The protocol has no state for silence; the player bar shows it.
            Player.Status.PLAYING, Player.Status.SILENT -> "playing"
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
            "ready" -> {
                sendState(player.state.value)
                isPageReady = true
                openPendingLink()
            }
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
            "describe" -> {
                player.describe(ChannelDescription.all(message.optJSONObject("channels")))
                openPendingLink()
            }
            "help" -> {
                _help.value = Help.fromJson(message.optJSONObject("content"))
                showHelpForFirstTime()
            }
            "showHelp" -> showHelp(true)
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
            showHelpForFirstTime()
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
            // Nobody sees the page while paused; load it when it is visible again.
            if (isPaused) {
                needsReload = true
                return true
            }
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
        /** Long enough for the player bar to have gone, with the focused button in it. */
        const val FOCUS_DELAY_MILLIS = 400L
    }
}

/**
 * The web view, telling [onPageBottom] where the bottom of the page is in its own
 * coordinates, for the settings button over it: at the bottom of a page shorter than the
 * view, and moving with the page when it scrolls. When keyboard focus leaves the page,
 * [onFocusLeaves] moves it to the native controls.
 */
@SuppressLint("ViewConstructor")
private class FrontendWebView(
    context: Context,
    private val onPageBottom: (Int) -> Unit,
    private val onFocusLeaves: (forward: Boolean) -> Boolean,
) : WebView(context) {
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // The page's height changes as it renders, and there is no callback for that.
        placeButton()
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        placeButton()
    }

    private fun placeButton() {
        onPageBottom(max(computeVerticalScrollRange(), height) - scrollY)
    }

    /**
     * Asked by the page when Tab or Shift+Tab leaves its last or first element. Once the
     * controls have the focus, returns what has it, so the page leaves it there. The page
     * asks with FOCUS_FORWARD or FOCUS_BACKWARD, which the parameter's annotation leaves out.
     */
    @SuppressLint("SwitchIntDef")
    override fun focusSearch(direction: Int): View? {
        val forward = when (direction) {
            FOCUS_FORWARD -> true
            FOCUS_BACKWARD -> false
            else -> return super.focusSearch(direction)
        }
        return if (onFocusLeaves(forward)) rootView.findFocus() else super.focusSearch(direction)
    }

    /**
     * Shift+Tab from the controls after the page enters it at its first element; the
     * page's last one is where it should go.
     */
    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        if (focused && direction == FOCUS_BACKWARD) evaluateJavascript(FOCUS_LAST_SCRIPT, null)
    }

    private companion object {
        /** Focuses the last element Tab would reach in the page. */
        const val FOCUS_LAST_SCRIPT = """(() => {
            const elements = [...document.querySelectorAll('a[href], button, input, select, textarea, [tabindex]')]
                .filter((e) => e.tabIndex >= 0 && !e.disabled && e.getClientRects().length > 0);
            elements.at(-1)?.focus();
        })()"""
    }
}

/**
 * What a link that opens the app points to, from its fragment: a talk by its GUID, e.g.
 * `https://audio.c3lingo.org/#4f9e1c2a-…`, or a channel or hall by its name, e.g.
 * `https://audio.c3lingo.org/#Saal_1_0_Floor` or `c3lingo://#Saal_1`.
 */
sealed interface AppLink {
    data class Talk(val guid: String) : AppLink
    data class Name(val name: String) : AppLink

    companion object {
        private val GUID = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

        fun fromUri(url: Uri): AppLink? {
            val fragment = url.fragment?.takeIf { it.isNotEmpty() } ?: return null
            return if (GUID.matches(fragment)) Talk(fragment) else Name(fragment)
        }
    }
}
