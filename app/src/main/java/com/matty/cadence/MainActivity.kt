package com.matty.cadence

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.matty.cadence.data.Library
import com.matty.cadence.ui.CadenceApp
import com.matty.cadence.ui.Prefs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as CadenceApplication
        setContent { CadenceApp(app.library, app.prefs) }
    }
}
