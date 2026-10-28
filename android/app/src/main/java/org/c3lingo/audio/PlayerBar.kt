package org.c3lingo.audio

import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.SpeakerGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.mediarouter.app.SystemOutputSwitcherDialogController

/**
 * The native playback controls, shown at the bottom while a channel is selected.
 *
 * Narrow (portrait phones): everything centred, the hall and language, the current
 * talk, the status and, if needed, a message that explains it, with the controls below.
 * Wide: the same information on the left, the controls on the right, once the
 * information has at least twice as much room as the controls take.
 */
@Composable
fun PlayerBar(state: Player.State, player: Player, description: ChannelDescription?) {
    Surface(tonalElevation = 3.dp) {
        BoxWithConstraints(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
        ) {
            val title = description?.title ?: state.channel?.displayName.orEmpty()
            if (maxWidth - WIDE_START_PADDING - CONTROLS_WIDTH >= CONTROLS_WIDTH * 2) {
                // The buttons' own padding is enough at the end.
                Row(Modifier.padding(start = WIDE_START_PADDING, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Title(title, Modifier.weight(1f, fill = false))
                            Status(state)
                        }
                        description?.talk?.let { Talk(it) }
                        Message(state)
                    }
                    Controls(state, player)
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Title(title)
                    description?.talk?.let { Talk(it, TextAlign.Center) }
                    Status(state)
                    Message(state, TextAlign.Center)
                    Controls(state, player)
                }
            }
        }
    }
}

private val WIDE_START_PADDING = 16.dp
/** Twice the usual size of an icon button, so they are easy to hit. */
private val BUTTON_SIZE = 80.dp
private val BUTTON_SPACING = 8.dp
private const val BUTTON_COUNT = 4
/** The width of [Controls], which the bar uses to decide where they go. */
private val CONTROLS_WIDTH = BUTTON_SIZE * BUTTON_COUNT + BUTTON_SPACING * (BUTTON_COUNT - 1)

@Composable
private fun Title(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** The current or next talk, a link to its page in the browser if the schedule has one. */
@Composable
private fun Talk(talk: ChannelDescription.Talk, textAlign: TextAlign? = null) {
    val context = LocalContext.current
    val url = talk.url
    val modifier = if (url != null) {
        Modifier.clickable(onClickLabel = stringResource(R.string.open_talk)) {
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
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun Status(state: Player.State, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusIndicator(state)
        Text(
            statusText(state),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Explains the status: that nothing is being broadcast, or why there's no connection. */
@Composable
private fun Message(state: Player.State, textAlign: TextAlign? = null) {
    val text = when (state.status) {
        Player.Status.WAITING -> stringResource(R.string.waiting_message)
        Player.Status.CONNECTING, Player.Status.RECONNECTING -> state.lastError
        else -> null
    } ?: return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = textAlign,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Controls(state: Player.State, player: Player) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(BUTTON_SPACING)) {
        if (state.isMuted) {
            ControlButton(Icons.AutoMirrored.Filled.VolumeOff, stringResource(R.string.unmute)) { player.setMuted(false) }
        } else {
            ControlButton(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.mute)) { player.setMuted(true) }
        }
        if (state.status == Player.Status.PAUSED) {
            ControlButton(Icons.Filled.PlayArrow, stringResource(R.string.play), player::togglePause)
        } else {
            ControlButton(Icons.Filled.Pause, stringResource(R.string.pause), player::togglePause)
        }
        // Bluetooth, wired and other outputs, through the system's output switcher.
        ControlButton(Icons.Outlined.SpeakerGroup, stringResource(R.string.audio_output)) {
            SystemOutputSwitcherDialogController.showDialog(context)
        }
        ControlButton(Icons.Filled.Stop, stringResource(R.string.stop), player::stop)
    }
}

/** A standard icon button at [BUTTON_SIZE]. */
@Composable
private fun ControlButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(BUTTON_SIZE)) {
        Icon(icon, description, Modifier.size(48.dp))
    }
}

@Composable
private fun StatusIndicator(state: Player.State) {
    when (state.status) {
        Player.Status.PLAYING -> {
            val pulse = rememberInfiniteTransition(label = "live")
            val alpha = if (state.isMuted) {
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
    Player.Status.PAUSED -> stringResource(R.string.status_paused)
    // The message below says what's wrong.
    Player.Status.RECONNECTING ->
        stringResource(if (state.lastError != null) R.string.status_connection_problem else R.string.status_reconnecting)
}
