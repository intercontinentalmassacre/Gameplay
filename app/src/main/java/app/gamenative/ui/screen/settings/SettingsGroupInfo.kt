package app.gamenative.ui.screen.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.Constants
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.component.dialog.LibrariesDialog
import app.gamenative.ui.theme.settingsTileColors
import com.alorma.compose.settings.ui.SettingsGroup
import app.gamenative.ui.component.settings.SettingsMenuLink

@Composable
fun SettingsGroupInfo() {
    SettingsGroup() {
        val context = LocalContext.current
        var showLibrariesDialog by rememberSaveable { mutableStateOf(false) }

        LibrariesDialog(
            visible = showLibrariesDialog,
            onDismissRequest = { showLibrariesDialog = false },
        )

        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_info_source_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_info_source_subtitle)) },
            onClick = { context.openExternalLink(Constants.Misc.GITHUB_LINK) },
        )

        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_info_libraries_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_info_libraries_subtitle)) },
            onClick = { showLibrariesDialog = true },
        )

        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_support_boosty_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_support_boosty_subtitle)) },
            onClick = { context.openExternalLink(Constants.Misc.BOOSTY_DONATION_LINK) },
        )

        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_support_donationalerts_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_support_donationalerts_subtitle)) },
            onClick = { context.openExternalLink(Constants.Misc.DONATIONALERTS_DONATION_LINK) },
        )

        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_info_privacy_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_info_privacy_subtitle)) },
            onClick = {
                context.openExternalLink(Constants.Misc.PRIVACY_LINK)
            },
        )
    }
}

/**
 * The companion screen is hosted in a [android.app.Presentation], whose Compose
 * context is not an [Activity]. Browser intents from it must create a new task.
 */
private fun Context.openExternalLink(url: String) {
    startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            if (this@openExternalLink !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        },
    )
}
