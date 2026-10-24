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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.mediarouter.app.SystemOutputSwitcherDialogController

/**
 * The native playback controls, shown at the bottom while a channel is selected.
 *
 * Narrow (portrait phones): the hall and language on one line, status and controls
 * below. Wide: hall, language, status and the current talk on the left, the controls
 * on the right. Below that, if needed, a message that explains the status.
 */
@Composable
fun PlayerBar(state: Player.State, player: Player, description: ChannelDescription?) {
    Surface(tonalElevation = 3.dp) {
        BoxWithConstraints(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        ) {
            val title = description?.title ?: state.channel?.displayName.orEmpty()
            if (maxWidth >= WIDE_WIDTH) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                Column {
                    Title(title, Modifier.padding(end = 12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Status(state, Modifier.weight(1f))
                        Controls(state, player)
                    }
                    Message(state, Modifier.padding(end = 12.dp))
                }
            }
        }
    }
}

/** From this width on, the bar puts the controls beside the channel information. */
private val WIDE_WIDTH = 520.dp

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
private fun Talk(talk: ChannelDescription.Talk) {
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
        talkText(talk),
        style = MaterialTheme.typography.bodyMedium,
        color = if (url != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun Message(state: Player.State, modifier: Modifier = Modifier) {
    val text = when (state.status) {
        Player.Status.WAITING -> stringResource(R.string.waiting_message)
        Player.Status.CONNECTING, Player.Status.RECONNECTING -> state.lastError
        else -> null
    } ?: return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun Controls(state: Player.State, player: Player) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = { player.setMuted(!state.isMuted) }) {
            if (state.isMuted) {
                Icon(Icons.AutoMirrored.Filled.VolumeOff, stringResource(R.string.unmute))
            } else {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.mute))
            }
        }
        IconButton(onClick = player::togglePause) {
            if (state.status == Player.Status.PAUSED) {
                Icon(Icons.Filled.PlayArrow, stringResource(R.string.play))
            } else {
                Icon(Icons.Filled.Pause, stringResource(R.string.pause))
            }
        }
        // Bluetooth, wired and other outputs, through the system's output switcher.
        IconButton(onClick = { SystemOutputSwitcherDialogController.showDialog(context) }) {
            Icon(Icons.Outlined.SpeakerGroup, stringResource(R.string.audio_output))
        }
        IconButton(onClick = player::stop) {
            Icon(Icons.Filled.Close, stringResource(R.string.stop))
        }
    }
}

/**
 * The current or next talk, e.g. "Since 14:30: Opening" or "Next: Day 2, 10:00: Keynote",
 * worded like the frontend's hall cards.
 */
@Composable
private fun talkText(talk: ChannelDescription.Talk): String {
    val time = talk.day?.let { stringResource(R.string.talk_day, it, talk.start) } ?: talk.start
    return stringResource(if (talk.started) R.string.talk_since else R.string.talk_next, time, talk.title)
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
