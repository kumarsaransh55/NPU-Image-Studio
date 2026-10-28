package studio.npu.x200t.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import studio.npu.sd.ModelType
import studio.npu.x200t.models.CatalogModel
import studio.npu.x200t.models.DownloadState
import studio.npu.x200t.models.Downloads
import studio.npu.x200t.models.ModelCatalog

@Composable
fun ModelsScreen(vm: StudioViewModel, modifier: Modifier) {
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val create by vm.create.collectAsStateWithLifecycle()
    val importStatus by vm.importStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var browsingCatalog by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = browsingCatalog) { browsingCatalog = false }
    var pickedFolder by remember { mutableStateOf<Uri?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> pickedFolder = uri }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Spacer(Modifier.height(16.dp))
            Text(if (browsingCatalog) "Add model" else "Models", style = MaterialTheme.typography.titleLarge)
            Hint(
                "Download a ready-to-use model once: compact models are about 1.1-1.4 GB, full models about 4.1 GB. " +
                    "Use Wi-Fi and keep the app open; downloads can be paused and resumed. " +
                    "After that, image generation is completely offline. Free space: ${Downloads.gb(vm.freeSpace())}.",
            )
            Button(onClick = { browsingCatalog = !browsingCatalog }) {
                Text(if (browsingCatalog) "Back to my models" else "Add model")
            }
            if (browsingCatalog) {
                Hint("Choose a model below and tap Download. No browser, PC or manual conversion needed. " +
                    "Smaller downloads are not necessarily faster than DreamShaper LCM.")
            } else if (installed.isEmpty()) {
                Hint("No models installed yet. Tap Add model to browse one-tap downloads, or import your own folder below.")
            }
            Spacer(Modifier.height(8.dp))
        }
        val visibleModels = ModelCatalog.models.filter { m ->
            browsingCatalog || installed.any { it.id == m.id } || downloads.containsKey(m.id) || vm.downloadedBytes(m) > 0
        }
        visibleModels.forEachIndexed { index, m ->
            if (browsingCatalog && (index == 0 || m.compact != visibleModels[index - 1].compact)) {
                item(key = if (m.compact) "compact-heading" else "full-heading") {
                    SectionTitle(if (m.compact) "Smaller downloads" else "Full-size models")
                }
            }
            item(key = m.id) {
                CatalogCard(
                    m = m,
                    installed = installed.any { it.id == m.id },
                    selected = create.modelId == m.id,
                    state = downloads[m.id],
                    partialBytes = vm.downloadedBytes(m),
                    onDownload = { vm.startDownload(m) },
                    onPause = { vm.pauseDownload(m.id) },
                    onDelete = { vm.deleteModel(m.id) },
                    onUse = { vm.selectModel(m.id) },
                )
            }
        }
        val custom = installed.filter { ModelCatalog.byId(it.id) == null }
        if (custom.isNotEmpty()) item { SectionTitle("Your own models") }
        items(custom, key = { it.id }) { m ->
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(m.name, style = MaterialTheme.typography.titleMedium)
                    Hint("Type: ${m.type?.label ?: "auto-detect"} · folder ${m.model.dir.name}")
                    ButtonRow {
                        Button(onClick = { vm.selectModel(m.id) }, enabled = create.modelId != m.id) {
                            Text(if (create.modelId == m.id) "In use" else "Use")
                        }
                        OutlinedButton(onClick = { vm.deleteModel(m.id) }) { Text("Delete") }
                    }
                }
            }
        }
        item {
            SectionTitle("Advanced: import your own model")
            Hint(
                "Any Stable Diffusion 1.5 model in ONNX format works (folders: text_encoder, unet, vae_decoder, tokenizer). " +
                    "Download it on a PC, copy the folder to the phone, then import it here - or copy it over USB directly into:\n" +
                    vm.modelsFolder(),
            )
            OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Import a model folder...")
            }
            importStatus?.let { Hint(it) }
            Spacer(Modifier.height(32.dp))
        }
    }

    pickedFolder?.let { uri ->
        val defaultName = remember(uri) { DocumentFile.fromTreeUri(context, uri)?.name ?: "My model" }
        ImportDialog(defaultName, onDismiss = { pickedFolder = null }) { name, type ->
            pickedFolder = null
            vm.importFolder(uri, name, type)
        }
    }
}

@Composable
private fun CatalogCard(
    m: CatalogModel,
    installed: Boolean,
    selected: Boolean,
    state: DownloadState?,
    partialBytes: Long,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onDelete: () -> Unit,
    onUse: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(m.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(m.description, style = MaterialTheme.typography.bodyMedium)
            Hint("${m.type.label} · ${Downloads.gb(m.totalBytes)} · ${m.license}")
            Hint("Tapping Download accepts the model's licence and its use restrictions. Models are not covered by the app's GPL licence.")
            val running = state?.running == true
            val done = if (running) state!!.done else partialBytes
            if (!installed && (running || done > 0)) {
                LinearProgressIndicator(progress = { (done.toFloat() / m.totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Hint("${Downloads.gb(done)} of ${Downloads.gb(m.totalBytes)}" + if (running) "" else " (paused)")
            }
            state?.error?.takeIf { !running && it != "Paused" }?.let { ErrorText(it) }
            ButtonRow {
                when {
                    installed -> {
                        Button(onClick = onUse, enabled = !selected) { Text(if (selected) "In use" else "Use") }
                        OutlinedButton(onClick = onDelete) { Text("Delete") }
                    }
                    running -> OutlinedButton(onClick = onPause) { Text("Pause") }
                    else -> {
                        Button(onClick = onDownload) { Text(if (done > 0) "Resume download" else "Download") }
                        if (done > 0) OutlinedButton(onClick = onDelete) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportDialog(defaultName: String, onDismiss: () -> Unit, onImport: (String, ModelType?) -> Unit) {
    var name by remember { mutableStateOf(defaultName) }
    var type by remember { mutableStateOf<ModelType?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import model") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Text("What kind of model is it?")
                val options = listOf<Pair<ModelType?, String>>(
                    null to "Normal SD 1.5 (or LCM - detected automatically)",
                    ModelType.LCM to "LCM (few-step)",
                    ModelType.RECTIFIED_FLOW to "InstaFlow / rectified flow",
                )
                options.forEach { (t, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().selectable(selected = type == t, onClick = { type = t }),
                    ) {
                        RadioButton(selected = type == t, onClick = { type = t })
                        Spacer(Modifier.width(4.dp))
                        Text(label)
                    }
                }
                Hint("Copying a 4 GB model takes a minute or two.")
            }
        },
        confirmButton = { TextButton(onClick = { onImport(name.ifBlank { defaultName }, type) }) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
