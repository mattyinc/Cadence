package com.matty.cadence

import android.app.Application
import com.matty.cadence.data.Library
import com.matty.cadence.ui.Prefs

/** Holds the library and preferences for the whole app (survives rotation). */
class CadenceApplication : Application() {
    lateinit var library: Library
        private set
    lateinit var prefs: Prefs
        private set

    override fun onCreate() {
        super.onCreate()
        library = Library(this)
        prefs = Prefs(this)
    }
}
