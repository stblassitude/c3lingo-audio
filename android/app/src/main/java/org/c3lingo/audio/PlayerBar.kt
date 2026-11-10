package org.c3lingo.audio

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.SpeakerGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.mediarouter.app.SystemOutputSwitcherDialogController

/**
 * The native playback controls, shown at the bottom while a channel is selected.
 *
 * Narrow (portrait phones): everything centred, the hall and language, the current
 * talk, the status and, if needed, a message that explains it, with the controls below.
 * Wide: the same information on the left, the controls on the right, once the
 * information has at least twice as much room as the controls take.
 *
 * TalkBack reads the status together with the message that explains it, and announces it
 * when it changes: the page doesn't announce the state while the app plays.
 * [onStop] is told whether the Stop button had keyboard focus. [firstFocus] and
 * [lastFocus] move keyboard focus to the first and last of its controls, from the page.
 */
@Composable
fun PlayerBar(
    state: Player.State,
    player: Player,
    subtitles: Subtitles,
    description: ChannelDescription?,
    firstFocus: FocusRequester,
    lastFocus: FocusRequester,
    onStop: (Boolean) -> Unit,
) {
    // The talk comes first if it is a link; otherwise the first control does.
    val talkFocus = firstFocus.takeIf { description?.talk?.url != null }
    val controlsFocus = Focus(first = firstFocus.takeIf { talkFocus == null }, last = lastFocus)
    Surface(tonalElevation = 3.dp) {
        BoxWithConstraints(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
        ) {
            val title = description?.title ?: state.channel?.displayName.orEmpty()
            if (maxWidth - WIDE_START_PADDING - CONTROLS_WIDTH >= CONTROLS_WIDTH * 2) {
                // The buttons' own padding is enough at the end.
                Row(Modifier.padding(start = WIDE_START_PADDING, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Title(title, Modifier.weight(1f, fill = false))
                            Status(state)
                        }
                        description?.talk?.let { Talk(it, focusRequester = talkFocus) }
                        Message(state)
                    }
                    Controls(state, player, subtitles, BUTTON_SIZE, controlsFocus, onStop)
                }
            } else {
                // Smaller where five of them don't fit, e.g. on small phones or with a
                // larger display size, but never below the minimum size to tap.
                val narrowButtonSize = ((maxWidth - NARROW_PADDING * 2 - BUTTON_SPACING * (BUTTON_COUNT - 1)) / BUTTON_COUNT)
                    .coerceIn(MIN_BUTTON_SIZE, BUTTON_SIZE)
                Column(
                    Modifier.fillMaxWidth().padding(start = NARROW_PADDING, end = NARROW_PADDING, top = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Title(title)
                    description?.talk?.let { Talk(it, TextAlign.Center, talkFocus) }
                    Status(state)
                    Message(state, TextAlign.Center)
                    Controls(state, player, subtitles, narrowButtonSize, controlsFocus, onStop)
                }
            }
        }
    }
}

private val WIDE_START_PADDING = 16.dp
private val NARROW_PADDING = 16.dp
/** Twice the usual size of an icon button, so they are easy to hit. */
private val BUTTON_SIZE = 80.dp
/** The minimum size to tap. */
private val MIN_BUTTON_SIZE = 48.dp
private val BUTTON_SPACING = 8.dp
private const val BUTTON_COUNT = 5
/** The width of [Controls], which the bar uses to decide where they go. */
private val CONTROLS_WIDTH = BUTTON_SIZE * BUTTON_COUNT + BUTTON_SPACING * (BUTTON_COUNT - 1)

/**
 * At large font sizes, text wraps instead of being cut off, to twice as many lines, which
 * leaves room for the page even with a long talk title.
 */
@Composable
private fun lines(count: Int): Int = if (LocalDensity.current.fontScale >= LARGE_FONT_SCALE) count * 2 else count

private const val LARGE_FONT_SCALE = 1.3f

/** Whether animations are on: off with Remove animations, or animation scale off in the developer options. */
@Composable
fun animationsEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
}

@Composable
private fun Title(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        maxLines = lines(1),
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** The current or next talk, a link to its page in the browser if the schedule has one. */
@Composable
private fun Talk(talk: ChannelDescription.Talk, textAlign: TextAlign? = null, focusRequester: FocusRequester? = null) {
    val context = LocalContext.current
    val url = talk.url
    val modifier = if (url != null) {
        Modifier.focusRequester(focusRequester).clickable(onClickLabel = stringResource(R.string.open_talk), role = Role.Button) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, url))
            } catch (e: ActivityNotFoundException) {
                Log.w("PlayerBar", "no app to open $url")
            }
        }
    } else {
        Modifier
    }
    Text(
        talk.text(context),
        style = MaterialTheme.typography.bodyMedium,
        color = if (url != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = textAlign,
        maxLines = lines(1),
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** The status; for TalkBack with the message below it, and announced when it changes. */
@Composable
private fun Status(state: Player.State, modifier: Modifier = Modifier) {
    val status = statusText(state)
    val spoken = listOfNotNull(status, messageText(state)).joinToString(". ")
    Row(
        modifier.clearAndSetSemantics {
            contentDescription = spoken
            liveRegion = LiveRegionMode.Polite
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusIndicator(state)
        Text(
            status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = lines(1),
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Explains the status: that nothing is being broadcast or can be heard, or why there's no connection. */
@Composable
private fun messageText(state: Player.State): String? = when (state.status) {
    Player.Status.WAITING -> stringResource(R.string.waiting_message)
    Player.Status.SILENT -> stringResource(R.string.silent_message)
    Player.Status.CONNECTING, Player.Status.RECONNECTING -> state.lastError
    else -> null
}

/** The message, which TalkBack reads with the status. */
@Composable
private fun Message(state: Player.State, textAlign: TextAlign? = null) {
    val text = messageText(state) ?: return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = textAlign,
        maxLines = lines(2),
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clearAndSetSemantics {},
    )
}

@Composable
private fun Controls(state: Player.State, player: Player, subtitles: Subtitles, buttonSize: Dp, focus: Focus, onStop: (Boolean) -> Unit) {
    val context = LocalContext.current
    val isShowingSubtitles by subtitles.isShowing.collectAsStateWithLifecycle()
    val subtitlesAvailable by subtitles.isAvailable.collectAsStateWithLifecycle()
    val stopInteraction = remember { MutableInteractionSource() }
    val isStopFocused by stopInteraction.collectIsFocusedAsState()
    Row(horizontalArrangement = Arrangement.spacedBy(BUTTON_SPACING)) {
        if (state.isMuted) {
            ControlButton(Icons.AutoMirrored.Filled.VolumeOff, stringResource(R.string.unmute), buttonSize, focus.first) { player.setMuted(false) }
        } else {
            ControlButton(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.mute), buttonSize, focus.first) { player.setMuted(true) }
        }
        if (state.status == Player.Status.PAUSED) {
            ControlButton(Icons.Filled.PlayArrow, stringResource(R.string.play), buttonSize, onClick = player::togglePause)
        } else {
            ControlButton(Icons.Filled.Pause, stringResource(R.string.pause), buttonSize, onClick = player::togglePause)
        }
        // Also where the device can't make subtitles: they then explain why.
        if (isShowingSubtitles) {
            ControlButton(Icons.Filled.ClosedCaption, stringResource(R.string.subtitles_hide), buttonSize, onClick = subtitles::toggle)
        } else {
            ControlButton(Icons.Outlined.ClosedCaption, stringResource(R.string.subtitles_show), buttonSize, enabled = subtitlesAvailable, onClick = subtitles::toggle)
        }
        // Bluetooth, wired and other outputs, through the system's output switcher.
        ControlButton(Icons.Outlined.SpeakerGroup, stringResource(R.string.audio_output), buttonSize) {
            SystemOutputSwitcherDialogController.showDialog(context)
        }
        ControlButton(Icons.Filled.Stop, stringResource(R.string.stop), buttonSize, focus.last, stopInteraction) { onStop(isStopFocused) }
    }
}

/** Where keyboard focus enters the controls from the page, if it does. */
private class Focus(val first: FocusRequester?, val last: FocusRequester?)

/** Attaches [requester] if there is one. */
private fun Modifier.focusRequester(requester: FocusRequester?): Modifier =
    if (requester != null) focusRequester(requester) else this

/** A standard icon button at [size], its icon in proportion. */
@Composable
private fun ControlButton(
    icon: ImageVector,
    description: String,
    size: Dp,
    focusRequester: FocusRequester? = null,
    interactionSource: MutableInteractionSource? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick, Modifier.focusRequester(focusRequester).size(size), enabled = enabled, interactionSource = interactionSource) {
        Icon(icon, description, Modifier.size(size * 0.6f))
    }
}

@Composable
private fun StatusIndicator(state: Player.State) {
    when (state.status) {
        Player.Status.PLAYING -> {
            val pulse = rememberInfiniteTransition(label = "live")
            val alpha = if (state.isMuted || !animationsEnabled()) {
                1f
            } else {
                pulse.animateFloat(1f, 0.3f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha").value
            }
            Box(Modifier.size(8.dp).alpha(alpha).background(Color.Red, CircleShape))
        }
        Player.Status.CONNECTING, Player.Status.RECONNECTING ->
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
        else -> Unit
    }
}

@Composable
private fun statusText(state: Player.State): String = when (state.status) {
    Player.Status.IDLE -> ""
    Player.Status.CONNECTING -> stringResource(R.string.status_connecting)
    Player.Status.WAITING -> stringResource(R.string.status_waiting)
    Player.Status.PLAYING -> stringResource(if (state.isMuted) R.string.status_live_muted else R.string.status_live)
    Player.Status.SILENT -> stringResource(R.string.status_silent)
    Player.Status.PAUSED -> stringResource(R.string.status_paused)
    // The message below says what's wrong.
    Player.Status.RECONNECTING ->
        stringResource(if (state.lastError != null) R.string.status_connection_problem else R.string.status_reconnecting)
}
