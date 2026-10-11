package com.ghidroid

import android.app.Application
import com.ghidroid.data.Library
import com.ghidroid.data.SettingsStore

class GhidroidApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var library: Library
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsStore(this)
        library = Library(this)
    }

    companion object {
        lateinit var instance: GhidroidApp
            private set
    }
}
