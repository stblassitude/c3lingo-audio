package org.c3lingo.audio

import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var frontend: Frontend
    private var controller: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The volume keys control playback volume even while nothing is playing.
        volumeControlStream = AudioManager.STREAM_MUSIC

        frontend = Frontend(this, app.player, lifecycleScope)
        applyLaunchOptions(intent)
        // A recreated activity gets the intent that opened it again.
        if (savedInstanceState == null) intent.data?.let(frontend::open)
        setContent {
            C3lingoTheme {
                AppScreen(frontend, app.player, app.subtitles, app.settings)
            }
        }

        lifecycleScope.launch {
            var first = true
            app.settings.serverURL.collect { url ->
                if (first) {
                    frontend.load(url)
                } else {
                    // A different server: start over as if the app had just been launched.
                    app.player.stop()
                    frontend.restart(url)
                }
                first = false
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyLaunchOptions(intent)
        intent.data?.let(frontend::open)
    }

    override fun onStart() {
        super.onStart()
        frontend.resume()
        // Connecting to the session starts the playback service, which then keeps itself
        // running in the foreground while a channel is playing.
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java)))
            .buildAsync()
    }

    override fun onStop() {
        // Playback runs without the page, which is paused while it can't be seen.
        frontend.pause()
        controller?.let(MediaController::releaseFuture)
        controller = null
        super.onStop()
    }

    override fun onDestroy() {
        frontend.destroy()
        super.onDestroy()
    }

    /**
     * For testing: `adb shell am start -n org.c3lingo.audio/.MainActivity -e serverURL <url> -e autoplay <channel>`
     * uses another server for this run and selects a channel once the page has loaded;
     * `--ez helpShown true` keeps the help from showing by itself.
     */
    private fun applyLaunchOptions(intent: Intent) {
        intent.getStringExtra("serverURL")?.let(app.settings::setOverride)
        intent.getStringExtra("autoplay")?.let { frontend.autoplay = it }
        if (intent.getBooleanExtra("helpShown", false)) frontend.helpShown = true
    }
}

@Composable
private fun AppScreen(frontend: Frontend, player: Player, subtitles: Subtitles, settings: ServerSettings) {
    val state by player.state.collectAsStateWithLifecycle()
    val loadError by frontend.loadError.collectAsStateWithLifecycle()
    val hasLoaded by frontend.hasLoaded.collectAsStateWithLifecycle()
    val serverURL by settings.serverURL.collectAsStateWithLifecycle()
    val descriptions by player.descriptions.collectAsStateWithLifecycle()
    val isShowingServerDialog by frontend.isShowingServerDialog.collectAsStateWithLifecycle()
    val help by frontend.help.collectAsStateWithLifecycle()
    val isShowingHelp by frontend.isShowingHelp.collectAsStateWithLifecycle()
    val isShowingSubtitles by subtitles.isShowing.collectAsStateWithLifecycle()
    val playing = state.channel != null

    if (isShowingServerDialog) {
        ServerDialog(settings, onReload = frontend::reload, onDismiss = { frontend.showServerDialog(false) })
    }
    help?.takeIf { isShowingHelp }?.let {
        HelpDialog(it, onDismiss = {
            frontend.showHelp(false)
            if (frontend.isScreenReaderOn) frontend.focusPageAfterHelp()
        })
    }
    val animate = animationsEnabled()

    // Keyboard focus from the page to the controls after it, the settings button and the
    // player bar, and back; Compose moves it among the controls by itself.
    val settingsFocus = remember { FocusRequester() }
    val playerBarFirst = remember { FocusRequester() }
    val playerBarLast = remember { FocusRequester() }
    SideEffect {
        frontend.onFocusLeavesPage = { forward ->
            val settings = frontend.isSettingsButtonFocusable
            val target = when {
                forward && settings -> settingsFocus
                forward && playing -> playerBarFirst
                !forward && playing -> playerBarLast
                !forward && settings -> settingsFocus
                else -> null
            }
            target?.requestFocus() == true
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        FrontendArea(
            frontend, subtitles, isShowingSubtitles, loadError, hasLoaded, serverURL, playing, { frontend.showServerDialog(true) },
            settingsFocus, Modifier.weight(1f).fillMaxWidth(),
        )
        AnimatedVisibility(
            playing,
            enter = if (animate) slideInVertically { it } + fadeIn() else EnterTransition.None,
            exit = if (animate) slideOutVertically { it } + fadeOut() else ExitTransition.None,
        ) {
            PlayerBar(state, player, subtitles, state.channel?.let { descriptions[it.name] }, playerBarFirst, playerBarLast) { hadKeyboardFocus ->
                val channel = state.channel?.name
                player.stop()
                // The bar goes away with the focused Stop button; hand the focus back to
                // the page, rather than leaving it at the screen's start.
                if (channel != null && (hadKeyboardFocus || frontend.isScreenReaderOn)) frontend.focusPage(channel)
            }
        }
    }
}

/** The frontend, covered by the splash or error screen until it has loaded, and by the subtitles while they show. */
@Composable
private fun FrontendArea(
    frontend: Frontend,
    subtitles: Subtitles,
    isShowingSubtitles: Boolean,
    loadError: String?,
    hasLoaded: Boolean,
    serverURL: Uri,
    playing: Boolean,
    onChangeServer: () -> Unit,
    settingsFocus: FocusRequester,
    modifier: Modifier,
) {
    Box(modifier) {
        // The frontend stays clear of the status bar, display cutouts and, without the
        // player bar, the navigation bar.
        val insets = if (playing) {
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        } else {
            WindowInsets.safeDrawing
        }
        Box(Modifier.fillMaxSize().windowInsetsPadding(insets).clipToBounds()) {
            AndroidView(
                factory = { frontend.view.also { (it.parent as? ViewGroup)?.removeView(it) } },
                modifier = Modifier.fillMaxSize(),
                update = { frontend.setCovered(loadError != null || !hasLoaded || isShowingSubtitles) },
            )
            if (!frontend.isCovered.value) {
                SettingsButton(frontend, onChangeServer, Modifier.align(Alignment.TopEnd).focusRequester(settingsFocus))
            }
        }
        AnimatedVisibility(isShowingSubtitles, enter = fadeIn(), exit = fadeOut()) {
            SubtitlesView(subtitles, Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)))
        }
        AnimatedVisibility(loadError != null, enter = fadeIn(), exit = fadeOut()) {
            LoadErrorView(loadError.orEmpty(), serverURL, onRetry = frontend::reload, onChangeServer = onChangeServer)
        }
        AnimatedVisibility(loadError == null && !hasLoaded, enter = fadeIn(), exit = fadeOut()) {
            SplashView()
        }
    }
}

/**
 * A small settings button at the bottom right of the page, over the page and scrolling
 * with it, see [Frontend.pageBottom]. Given keyboard focus, it scrolls the page to its end,
 * so it can be seen.
 */
@Composable
private fun SettingsButton(frontend: Frontend, onClick: () -> Unit, modifier: Modifier) {
    val margin = 8.dp
    FilledTonalIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = modifier
            .padding(end = margin)
            // Read while placing, so scrolling moves the button without recomposing.
            .offset { IntOffset(0, frontend.pageBottom.intValue - SETTINGS_BUTTON_SIZE.roundToPx() - margin.roundToPx()) }
            .size(SETTINGS_BUTTON_SIZE)
            .onFocusChanged { if (it.isFocused) frontend.revealSettingsButton() },
    ) {
        Icon(Icons.Filled.Settings, stringResource(R.string.server_settings), Modifier.size(20.dp))
    }
}

private val SETTINGS_BUTTON_SIZE = 48.dp

/** Shown until the frontend has loaded: the logo centred on the screen, with progress below it. */
@Composable
private fun SplashView() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Image(
            painterResource(R.drawable.logo),
            contentDescription = null,
            modifier = Modifier.size(128.dp).align(Alignment.Center),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            // Place below the logo rather than over it.
            modifier = Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(
                        (constraints.maxWidth - placeable.width) / 2,
                        constraints.maxHeight / 2 + (64 + 32).dp.roundToPx())
                }
            },
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Explains in plain words what to check, with the technical [reason] and the URL below. */
@Composable
private fun LoadErrorView(reason: String, url: Uri, onRetry: () -> Unit, onChangeServer: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
    ) {
        Icon(
            Icons.Outlined.WifiOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(44.dp).clearAndSetSemantics {},
        )
        Text(
            stringResource(R.string.load_error_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.load_error_message),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val detailColor = MaterialTheme.colorScheme.onSurfaceVariant
            Text(reason, style = MaterialTheme.typography.bodySmall, color = detailColor, textAlign = TextAlign.Center)
            Text(
                url.toString(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = detailColor,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
        TextButton(onClick = onChangeServer) { Text(stringResource(R.string.change_server)) }
    }
}
