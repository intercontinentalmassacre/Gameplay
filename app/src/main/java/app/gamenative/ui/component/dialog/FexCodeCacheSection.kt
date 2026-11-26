package app.gamenative.ui.component.dialog

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.fexcache.FexCacheState
import app.gamenative.fexcache.FexCacheStatus
import app.gamenative.fexcache.FexCodeCache
import com.alorma.compose.settings.ui.SettingsGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Experimental action row for the per-game launch settings. It is only rendered for a FEX content
 * build that actually ships FEXOfflineCompiler64.exe; the stock 2605 asset does not, so there is
 * no dead toggle for it.
 */
@Composable
fun FexCodeCacheSection(
    appId: String,
    enabled: Boolean,
    onPrepare: (() -> Unit)?,
) {
    if (!enabled || onPrepare == null) return
    val context = LocalContext.current
    var status by remember(appId) { mutableStateOf<FexCacheStatus?>(null) }
    var busy by remember(appId) { mutableStateOf(false) }
    var lastResult by remember(appId) { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        status = withContext(Dispatchers.IO) { FexCodeCache.status(context, appId) }
    }

    LaunchedEffect(appId) { refresh() }
    LaunchedEffect(status?.state) {
        while (status?.isBusy == true) {
            delay(1_000)
            refresh()
        }
    }

    val current = status ?: return
    if (!current.supported) return

    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.fex_cache_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = when (current.state) {
                    FexCacheState.READY -> stringResource(R.string.fex_cache_ready) + " · " +
                        Formatter.formatShortFileSize(context, current.cacheBytes)
                    FexCacheState.PREPARING -> stringResource(R.string.fex_cache_preparing)
                    FexCacheState.COMPILING -> stringResource(R.string.fex_cache_compiling)
                    FexCacheState.NEEDS_REPREPARE -> stringResource(R.string.fex_cache_needs_reprepare)
                    FexCacheState.FAILED -> stringResource(R.string.fex_cache_failed) +
                        current.reason.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                    else -> stringResource(R.string.fex_cache_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (current.canPrepare) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            onPrepare()
                        },
                    ) {
                        Text(stringResource(R.string.fex_cache_prepare))
                    }
                }
                if (current.canClear) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                                FexCodeCache.clear(context, appId)
                                busy = false
                                refresh()
                            }
                        },
                    ) {
                        Text(stringResource(R.string.fex_cache_clear))
                    }
                }
                if (current.canRecompile) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                                lastResult = FexCodeCache.recompile(context, appId)
                                busy = false
                                refresh()
                            }
                        },
                    ) {
                        Text(stringResource(R.string.fex_cache_recompile))
                    }
                }
            }
            lastResult?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
