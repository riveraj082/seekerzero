package dev.seekerzero.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.seekerzero.app.ui.navigation.SeekerZeroNavHost
import dev.seekerzero.app.ui.theme.SeekerZeroTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SeekerZeroTheme {
                SeekerZeroNavHost()
            }
        }
    }
}
