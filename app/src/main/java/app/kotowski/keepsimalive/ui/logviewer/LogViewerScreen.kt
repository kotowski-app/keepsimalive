package app.kotowski.keepsimalive.ui.logviewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.kotowski.keepsimalive.R
import app.kotowski.keepsimalive.ui.SystemScrollStateScrollIndicator
import app.kotowski.keepsimalive.ui.icons.ContentCopyIcon
import app.kotowski.keepsimalive.util.LogBuffer
import app.kotowski.keepsimalive.util.ToastUtil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var queryFilter by remember { mutableStateOf("") }

    var refreshCount by remember { mutableStateOf(0L) }

    LaunchedEffect(LogBuffer.refreshTrigger) {
        LogBuffer.refreshTrigger.collect { refreshCount = it }
    }

    val filteredEntries =
        remember(refreshCount, queryFilter) {
            if (queryFilter.isBlank()) {
                LogBuffer.entries
            } else {
                LogBuffer.entries.filter { it.message.contains(queryFilter, ignoreCase = true) }
            }
        }

    fun copyText(text: String) {
        if (text.isBlank()) {
            ToastUtil.show(context, R.string.log_viewer_nothing_to_copy)
            return
        }
        clipboardManager.setText(AnnotatedString(text))
        ToastUtil.show(context, R.string.log_viewer_copied)
    }

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = context.getString(R.string.log_viewer_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = context.getString(R.string.nav_back))
                    }
                },
                actions = {
                    // Icon-only: the copied content is the log buffer right next to it, so
                    // the content_copy icon needs no label.
                    IconButton(
                        onClick = { copyText(filteredEntries.joinToString("\n") { it.toString() }) },
                        enabled = filteredEntries.isNotEmpty(),
                        modifier = Modifier.testTag("log_viewer_copy"),
                    ) {
                        Icon(
                            imageVector = ContentCopyIcon,
                            contentDescription = context.getString(R.string.log_viewer_copy),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = queryFilter,
                    onValueChange = { queryFilter = it },
                    label = { Text(context.getString(R.string.log_viewer_search)) },
                    placeholder = { Text(context.getString(R.string.log_viewer_search_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 1.dp,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    if (filteredEntries.isEmpty()) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text =
                                    if (LogBuffer.entries.isEmpty()) {
                                        context.getString(R.string.log_viewer_empty)
                                    } else {
                                        context.getString(R.string.log_viewer_no_match)
                                    },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    } else {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                        ) {
                            filteredEntries.forEach { entry ->
                                Text(
                                    text = entry.toString(),
                                    style =
                                        MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { copyText(entry.toString()) }
                                            .padding(vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }

            SystemScrollStateScrollIndicator(
                scrollState = scrollState,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}
