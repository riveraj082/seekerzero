package dev.seekerzero.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.seekerzero.app.ui.theme.SeekerZeroColors
import dev.seekerzero.app.ui.theme.SeekerZeroTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeekerZeroTopAppBar(
    title: String,
    onBack: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null
) {
    TopAppBar(
        title = { Text(title, color = SeekerZeroColors.TextPrimary) },
        navigationIcon = {
            when {
                onBack != null -> IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = SeekerZeroColors.TextPrimary
                    )
                }
                onMenu != null -> IconButton(onClick = onMenu) {
                    Icon(
                        imageVector = Icons.Outlined.Menu,
                        contentDescription = "Open chats",
                        tint = SeekerZeroColors.TextPrimary
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = SeekerZeroColors.Background,
            titleContentColor = SeekerZeroColors.TextPrimary,
            navigationIconContentColor = SeekerZeroColors.TextPrimary
        )
    )
}

@Preview
@Composable
private fun TopAppBarPreview() {
    SeekerZeroTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            SeekerZeroTopAppBar(title = "Approvals", onBack = {})
        }
    }
}
