package studio.npu.x200t.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import studio.npu.sd.ModelType
import java.util.Locale
import kotlin.math.roundToInt

val SIZES = listOf(256 to 256, 384 to 384, 512 to 512, 512 to 768, 768 to 512)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateScreen(vm: StudioViewModel, modifier: Modifier, goToModels: () -> Unit, goToEngine: () -> Unit) {
    val st by vm.create.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val npu by vm.npu.collectAsStateWithLifecycle()
    val autoTest by vm.autoTest.collectAsStateWithLifecycle()
    val runEvents by vm.runEvents.collectAsStateWithLifecycle()
    val planVersion by vm.planVersion.collectAsStateWithLifecycle()
    val model = installed.firstOrNull { it.id == st.modelId }
    val type = model?.type
    val unetPlan = remember(planVersion, npu, autoTest, st.width, st.height) { vm.unetPlanShort() }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        NpuBadge(npu, unetPlan, onClick = goToEngine)
        autoTest?.let {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (installed.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("No model on the phone yet", style = MaterialTheme.typography.titleMedium)
                    Hint("Download one model once (about 4 GB, use Wi-Fi). After that, everything works offline.")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = goToModels) { Text("Go to Models") }
                }
            }
            return@Column
        }

        Picker("Model", model, installed, { it?.name ?: "Choose a model" }, { it?.let { m -> vm.selectModel(m.id) } },
            Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = st.prompt,
            onValueChange = vm::onPromptChanged,
            label = { Text("Describe the image") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "${st.tokenCount} / 75 word pieces" + if (st.tokenCount > 75) " - the end will be cut off" else "",
            style = MaterialTheme.typography.labelSmall,
            color = if (st.tokenCount > 75) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (StudioViewModel.usesNegative(type, st.guidance)) {
            OutlinedTextField(
                value = st.negative,
                onValueChange = { v -> vm.update { it.copy(negative = v) } },
                label = { Text("Avoid (negative prompt)") },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Switch(checked = st.live, onCheckedChange = { v -> vm.update { it.copy(live = v) } })
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Live mode", style = MaterialTheme.typography.bodyLarge)
                Hint("Re-draws as you type (fewer steps, fast decoder, fixed seed)")
            }
        }

        Spacer(Modifier.height(10.dp))
        ImageArea(st.image, st.width, st.height,
            if (st.busy) "Working... the first run loads/compiles the model (can take minutes on the NPU)" else "Your image appears here")
        if (st.busy || st.progress in 0.01f..0.99f) {
            LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        if (st.status.isNotEmpty()) Hint(st.status)
        st.info?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
        st.error?.let { ErrorText(it) }
        if (runEvents.isNotEmpty()) {
            Card(
                onClick = goToEngine,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("Some hardware didn't work, so the app switched:", style = MaterialTheme.typography.labelLarge)
                    runEvents.distinctBy { it.component to it.backend }.takeLast(4).forEach { e ->
                        Text("• ${e.component.label} on ${e.backend}: ${e.message.take(140)}",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                    }
                    Hint("Tap to open the Engine tab (choose hardware, see the full log).")
                }
            }
        }

        ButtonRow {
            if (st.busy && !st.live) {
                Button(onClick = vm::stop, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Close, null); Spacer(Modifier.width(6.dp)); Text("Stop")
                }
            } else {
                Button(onClick = { vm.generate() }, modifier = Modifier.weight(1f)) { Text("Generate") }
            }
            FilledTonalButton(onClick = vm::saveImage, enabled = st.image != null && !st.busy) {
                Icon(Icons.Filled.Done, null); Spacer(Modifier.width(4.dp)); Text("Save")
            }
            IconButton(onClick = vm::shareImage, enabled = st.image != null && !st.busy) { Icon(Icons.Filled.Share, "Share") }
        }

        SectionTitle("Settings")
        Text("Size", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SIZES.forEach { (w, h) ->
                FilterChip(
                    selected = st.width == w && st.height == h,
                    onClick = { vm.update { it.copy(width = w, height = h) } },
                    label = { Text(if (w == h) "$w" else "${w}×$h") },
                )
            }
        }
        Hint("512 is what these models were trained on. Each new size is compiled once on the NPU.")

        val range = StudioViewModel.stepRange(type)
        Text("Steps: ${st.steps}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        Slider(
            value = st.steps.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { v -> vm.update { it.copy(steps = v.roundToInt()) } },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
        )
        Hint(
            when (type) {
                ModelType.LCM -> "LCM: 4 is a good balance, 2 is fast, 6-8 a bit sharper."
                ModelType.RECTIFIED_FLOW -> "InstaFlow is made for 1 step."
                else -> "Classic SD 1.5: 15-25 steps. Each step runs the model twice (prompt + negative)."
            },
        )

        if (type != ModelType.RECTIFIED_FLOW) {
            Text("Prompt strength: ${String.format(Locale.US, "%.1f", st.guidance)}", style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp))
            Slider(value = st.guidance, onValueChange = { v -> vm.update { it.copy(guidance = (v * 2).roundToInt() / 2f) } },
                valueRange = 1f..12f)
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            OutlinedTextField(
                value = st.seed.toString(),
                onValueChange = { v -> v.toLongOrNull()?.let { s -> vm.update { it.copy(seed = s) } } },
                label = { Text("Seed") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = vm::newSeed) { Icon(Icons.Filled.Refresh, "New seed") }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Switch(checked = st.randomSeed, onCheckedChange = { v -> vm.update { it.copy(randomSeed = v) } })
                Text("random", style = MaterialTheme.typography.labelSmall)
            }
        }
        Hint("Same seed + same prompt = same image. Random gives a new picture every time.")

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Switch(checked = st.tinyDecoder, onCheckedChange = { v -> vm.update { it.copy(tinyDecoder = v) } })
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Fast decoder (TAESD)", style = MaterialTheme.typography.bodyLarge)
                Hint("Much faster final step, slightly softer details. Off = full quality VAE.")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
