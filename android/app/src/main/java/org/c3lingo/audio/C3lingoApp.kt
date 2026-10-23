package org.c3lingo.audio

import android.app.Application
import android.content.Context

/** Holds the objects that outlive any one activity: playback keeps going without the UI. */
class C3lingoApp : Application() {
    lateinit var settings: ServerSettings
        private set
    lateinit var player: Player
        private set

    override fun onCreate() {
        super.onCreate()
        settings = ServerSettings(this)
        player = Player(this, settings)
    }
}

val Context.app: C3lingoApp
    get() = applicationContext as C3lingoApp
