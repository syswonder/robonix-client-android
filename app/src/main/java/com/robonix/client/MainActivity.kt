package com.robonix.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.robonix.client.ui.i18n.AppStrings
import com.robonix.client.ui.i18n.LocalAppLanguage
import com.robonix.client.ui.navigation.AppNavigation
import com.robonix.client.ui.navigation.SharedViewModel
import com.robonix.client.ui.theme.Bg
import com.robonix.client.ui.theme.RobonixTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Uncaught exceptions are written to the log file by AppLogHooks (RobonixApp).
        AppLog.write("UI", "MainActivity onCreate")

        enableEdgeToEdge()
        setContent {
            AppLog.write("UI", "setContent composing")
            val sharedViewModel: SharedViewModel = hiltViewModel()
            val settings by sharedViewModel.settings.collectAsState()
            val appLanguage = remember(settings.language) { AppStrings.resolveLanguage(settings.language) }
            RobonixTheme {
                CompositionLocalProvider(LocalAppLanguage provides appLanguage) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Bg,
                    ) {
                        AppNavigation()
                    }
                }
            }
        }
    }
}
