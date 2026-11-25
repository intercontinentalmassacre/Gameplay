package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.service.CommunityControllerLayouts
import app.gamenative.utils.CommunityGamepadBindings
import com.winlator.inputcontrols.Binding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun CommunityLayoutsPanel(
    appId: Int,
    onApply: (Map<Int, Binding>) -> Unit,
    search: suspend (Int, String, Int) -> CommunityControllerLayouts.Page = CommunityControllerLayouts::search,
    download: suspend (CommunityControllerLayouts.Layout) -> String = CommunityControllerLayouts::download,
) {
    var query by rememberSaveable(appId) { mutableStateOf("") }
    var page by rememberSaveable(appId) { mutableIntStateOf(1) }
    var retry by remember { mutableIntStateOf(0) }
    var results by remember(appId, query, page, retry) { mutableStateOf<CommunityControllerLayouts.Page?>(null) }
    var error by remember(appId, query, page, retry) { mutableIntStateOf(0) }
    var selected by remember(appId, query, page) { mutableStateOf<CommunityControllerLayouts.Layout?>(null) }
    var bindings by remember(selected, retry) { mutableStateOf<Map<Int, Binding>?>(null) }
    var importError by remember(selected, retry) { mutableIntStateOf(0) }

    LaunchedEffect(appId, query, page, retry) {
        if (query.isNotBlank()) delay(350)
        try {
            results = search(appId, query.trim(), page)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = R.string.community_layout_search_error
        }
    }
    LaunchedEffect(selected, retry) {
        val layout = selected ?: return@LaunchedEffect
        try {
            val text = download(layout)
            val imported = withContext(Dispatchers.Default) { CommunityGamepadBindings.read(text) }
            if (imported.isEmpty()) importError = R.string.community_layout_unsupported else bindings = imported
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            importError = R.string.community_layout_download_error
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it; page = 1 },
            label = { Text(stringResource(R.string.community_layout_search)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        val data = results
        if (error != 0) {
            Text(stringResource(error))
            TextButton(onClick = { retry++ }) { Text(stringResource(R.string.connection_retry)) }
        } else if (data == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LazyColumn(Modifier.weight(1f)) {
                if (data.layouts.isEmpty()) item { Text(stringResource(R.string.community_layout_empty)) }
                items(data.layouts, key = { it.id }) { layout ->
                    Surface(onClick = { selected = layout }, modifier = Modifier.fillMaxWidth(),
                        color = if (selected == layout) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(layout.title, style = MaterialTheme.typography.titleMedium)
                            Text(listOf(layout.device, "#${layout.id}").filter(String::isNotBlank).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (data.total > CommunityControllerLayouts.PAGE_SIZE) item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { page-- }, enabled = page > 1) { Text(stringResource(R.string.community_layout_prev)) }
                        TextButton(onClick = { page++ }, enabled = page * CommunityControllerLayouts.PAGE_SIZE < data.total) {
                            Text(stringResource(R.string.community_layout_next))
                        }
                    }
                }
            }
            if (selected != null) {
                when {
                    importError != 0 -> {
                        Text(stringResource(importError))
                        if (importError == R.string.community_layout_download_error) {
                            TextButton(onClick = { retry++ }) { Text(stringResource(R.string.connection_retry)) }
                        }
                    }
                    bindings == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> {
                        Text(stringResource(R.string.community_layout_import, bindings!!.size - 1))
                        Button(onClick = { bindings?.let(onApply) }, modifier = Modifier.align(Alignment.End)) {
                            Text(stringResource(R.string.layout_apply))
                        }
                    }
                }
            }
        }
    }
}
