package dev.yukaribox.vpn.core

import android.app.Application
import android.content.Context

/** Holds the process-wide application context for non-Activity components. */
object AppContext {
    @Volatile
    lateinit var app: Application
        private set

    fun init(application: Application) {
        app = application
    }

    val context: Context get() = app
}
