package org.c3lingo.audio

import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps playback running in the background as a foreground service, and provides the
 * media session behind the notification, lock screen and Bluetooth controls.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, SessionPlayer(app.player, app.settings))
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

/**
 * Presents the [Player] to media3 as a live stream that can be paused and resumed. Stopping
 * from the system controls pauses, as on iOS, so the channel stays selected.
 */
@OptIn(UnstableApi::class)
private class SessionPlayer(
    private val player: Player,
    private val settings: ServerSettings,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val scope = MainScope()

    init {
        scope.launch { player.state.collect { invalidateState() } }
    }

    override fun getState(): State {
        val current = player.state.value
        val builder = State.Builder().setAvailableCommands(COMMANDS)
        val channel = current.channel
            ?: return builder
                .setPlaybackState(Media3Player.STATE_IDLE)
                .setPlayWhenReady(false, Media3Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .build()
        val metadata = MediaMetadata.Builder()
            .setTitle(channel.displayName)
            .setArtist(settings.serverURL.value.host ?: "c3lingo")
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build()
        val item = MediaItem.Builder().setMediaId(channel.name).setMediaMetadata(metadata).build()
        return builder
            .setPlaylist(listOf(
                MediaItemData.Builder(channel.name)
                    .setMediaItem(item)
                    .setIsDynamic(true)
                    .setIsSeekable(false)
                    .setLiveConfiguration(MediaItem.LiveConfiguration.UNSET)
                    .build()))
            .setPlayWhenReady(current.status != Player.Status.PAUSED, Media3Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(when (current.status) {
                Player.Status.PLAYING, Player.Status.PAUSED -> Media3Player.STATE_READY
                else -> Media3Player.STATE_BUFFERING
            })
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) player.resume() else player.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        player.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }

    private companion object {
        val COMMANDS: Media3Player.Commands = Media3Player.Commands.Builder()
            .addAll(
                Media3Player.COMMAND_PLAY_PAUSE,
                Media3Player.COMMAND_STOP,
                Media3Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Media3Player.COMMAND_GET_METADATA,
                Media3Player.COMMAND_GET_TIMELINE,
                Media3Player.COMMAND_RELEASE,
            )
            .build()
    }
}
