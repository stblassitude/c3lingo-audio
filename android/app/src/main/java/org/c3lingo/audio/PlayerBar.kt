package org.c3lingo.audio

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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

/** The native playback controls, shown at the bottom while a channel is selected. */
@Composable
fun PlayerBar(state: Player.State, player: Player) {
    val context = LocalContext.current
    val paused = state.status == Player.Status.PAUSED
    Surface(tonalElevation = 3.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(
                    state.channel?.displayName.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
            IconButton(onClick = { player.setMuted(!state.isMuted) }) {
                if (state.isMuted) {
                    Icon(Icons.AutoMirrored.Filled.VolumeOff, stringResource(R.string.unmute))
                } else {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.mute))
                }
            }
            IconButton(onClick = player::togglePause) {
                if (paused) {
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
    Player.Status.RECONNECTING -> state.lastError?.let { stringResource(R.string.status_reconnecting_error, it) }
        ?: stringResource(R.string.status_reconnecting)
}
