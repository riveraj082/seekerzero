package dev.seekerzero.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import dev.seekerzero.app.ui.navigation.SeekerZeroNavHost
import dev.seekerzero.app.ui.theme.SeekerZeroTheme

// FragmentActivity (rather than ComponentActivity) so androidx.biometric's
// BiometricPrompt can host its dialog — BiometricPrompt requires a
// FragmentActivity specifically.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SeekerZeroTheme {
                SeekerZeroNavHost()
            }
        }
    }
}
