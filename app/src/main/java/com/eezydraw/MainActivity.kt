package com.eezydraw

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.eezydraw.ui.EezyDrawApp
import com.eezydraw.ui.AppViewModel
import androidx.activity.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels {
        viewModelFactory {
            initializer {
                val container = (application as EezyDrawApplication).container
                AppViewModel(application, container.projects, container.settings, createSavedStateHandle())
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { EezyDrawApp(appViewModel) }
    }
}
