package dev.seekerzero.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import dev.seekerzero.app.config.ConfigManager
import dev.seekerzero.app.service.SeekerZeroService
import dev.seekerzero.app.ui.navigation.SeekerZeroNavHost
import dev.seekerzero.app.ui.theme.SeekerZeroTheme

// FragmentActivity (rather than ComponentActivity) so androidx.biometric's
// BiometricPrompt can host its dialog — BiometricPrompt requires a
// FragmentActivity specifically.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyContextIntent(intent)
        setContent {
            SeekerZeroTheme {
                SeekerZeroNavHost()
            }
        }
    }

    // singleTop: if a chat-reply notification fires while the activity is
    // already running, Android delivers the new intent here instead of
    // recreating the activity. Switch the active chat context so the UI
    // observes the change via ConfigManager.activeChatContextFlow.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyContextIntent(intent)
    }

    private fun applyContextIntent(intent: Intent?) {
        if (intent == null) return
        intent.getStringExtra(SeekerZeroService.EXTRA_CONTEXT_ID)?.let { ctxId ->
            if (ctxId.isNotBlank()) ConfigManager.activeChatContext = ctxId
        }
        intent.getStringExtra(SeekerZeroService.EXTRA_INITIAL_TAB)?.let { tab ->
            if (tab.isNotBlank()) ConfigManager.pendingInitialTab = tab
        }
    }
}
