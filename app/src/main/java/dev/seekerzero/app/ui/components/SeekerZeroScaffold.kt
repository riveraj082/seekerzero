package dev.seekerzero.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.seekerzero.app.ui.theme.SeekerZeroColors
import dev.seekerzero.app.ui.theme.SeekerZeroTheme

@Composable
fun SeekerZeroScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        topBar = {
            SeekerZeroTopAppBar(
                title = title,
                onBack = onBack,
                onMenu = onMenu,
                actions = actions
            )
        },
        containerColor = SeekerZeroColors.Background
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SeekerZeroColors.Background)
                .padding(inner)
        ) {
            content(PaddingValues(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

@Preview
@Composable
private fun ScaffoldPreview() {
    SeekerZeroTheme {
        SeekerZeroScaffold(title = "Approvals", onBack = {}) { pad ->
            Box(modifier = Modifier.padding(pad)) {
                Text("Screen body", color = SeekerZeroColors.TextPrimary)
            }
        }
    }
}
