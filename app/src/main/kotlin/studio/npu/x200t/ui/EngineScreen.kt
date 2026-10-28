package studio.npu.x200t.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import studio.npu.sd.Component
import studio.npu.x200t.engine.Accel
import studio.npu.x200t.engine.backendName

@Composable
fun EngineScreen(vm: StudioViewModel, modifier: Modifier) {
    val npu by vm.npu.collectAsStateWithLifecycle()
    val selfTest by vm.selfTest.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    val busy by vm.engineBusy.collectAsStateWithLifecycle()
    val disabled by vm.disabledAccels.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var systemLog by remember { mutableStateOf<String?>(null) }
    var cache by remember { mutableStateOf(vm.npuCache) }
    var strict by remember { mutableStateOf(vm.strict) }
    var refresh by remember { mutableIntStateOf(0) }
    val planVersion by vm.planVersion.collectAsStateWithLifecycle()
    val benchText by vm.benchText.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Engine & NPU", style = MaterialTheme.typography.titleLarge)
        Hint(
            "Your phone can reach its NPU two ways: Android's NNAPI and MediaTek's NeuroPilot plugin. " +
                "The app measures both (plus GPU and CPU) and, on Auto, uses the fastest.",
        )

        SectionTitle("NPU status")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(npu?.summary() ?: "Checking...", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(6.dp))
                Text(npu?.report() ?: "", fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
        if (disabled.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Switched off for safety", style = MaterialTheme.typography.titleSmall)
                    disabled.forEach { (id, why) ->
                        Text("${backendName(id)} crashed the app while $why, so the app now skips it.",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                        OutlinedButton(onClick = { vm.reEnable(id) }) { Text("Turn ${backendName(id)} back on") }
                    }
                    Hint("After turning it back on, close the app completely and open it again.")
                }
            }
        }
        ButtonRow {
            Button(onClick = vm::runSelfTest, enabled = !busy) { Text("Run NPU self-test") }
            OutlinedButton(onClick = {
                copyToClipboard(context, "NPU report", (npu?.report() ?: "") + "\n" + (selfTest ?: ""))
            }) { Text("Copy report") }
        }
        Hint("The self-test uses the small image decoder that ships inside the app, so it works before you download a model.")
        selfTest?.let {
            Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 15.sp, modifier = Modifier.padding(12.dp))
            }
        }

        SectionTitle("Where each part runs")
        Hint(
            "Making one image uses 4 separate networks. These settings are used by the Create and Animate tabs. " +
                "After each image, the Create tab shows exactly which chip each part ran on.",
        )
        Hint(
            "Auto = the fastest chip measured on your phone (self-test / UNet test below). " +
                "A specific chip = that chip, with the CPU as a backup if it fails (unless strict mode is on).",
        )
        ButtonRow {
            Button(onClick = { vm.resetToAuto(); strict = false; refresh++ }) { Text("Reset all to Auto") }
        }
        Hint("Recommended: Auto for everything. Use the menus below only to experiment.")
        key(refresh, planVersion) {
            listOf(
                Component.UNET to "Runs every step (4× per image with LCM) - this is what decides the speed",
                Component.VAE_DECODER to "Turns the result into pixels at the end (best quality)",
                Component.TINY_DECODER to "Fast pixel maker: live mode, previews, 'Fast decoder' switch",
                Component.TEXT_ENCODER to "Reads your prompt once (well under a second on the CPU)",
            ).forEach { (c, desc) ->
                Picker(c.label, vm.accel(c), Accel.entries, { it.label }, { a -> vm.setAccel(c, a); refresh++ },
                    Modifier.fillMaxWidth().padding(top = 8.dp))
                Hint(desc)
                Text(vm.planText(c), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
            Switch(checked = strict, onCheckedChange = { strict = it; vm.strict = it; refresh++ })
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Strict mode (force the chip I picked)")
                Hint("On: never switch to the CPU behind your back - you get an error instead. Off (normal): the app quietly uses the CPU if your chip fails.")
            }
        }

        key(refresh, planVersion, log.size) {
            val failed = vm.failedChips()
            if (failed.isNotEmpty()) {
                SectionTitle("Chips that failed before (skipped)")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        failed.forEach { (what, why) ->
                            Text("• $what", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(why.take(200), style = MaterialTheme.typography.bodySmall)
                        }
                        Hint("The app remembers these so it doesn't waste a slow NPU compile on them every time.")
                        OutlinedButton(onClick = { vm.retryFailed(); refresh++ }, modifier = Modifier.padding(top = 6.dp)) {
                            Text("Try them again")
                        }
                    }
                }
            }
        }

        SectionTitle("Find the fastest chip for the main model")
        Hint(
            "The self-test above uses a small network. This test times one real UNet step of the selected model, at the size " +
                "chosen in Create, on every chip - so Auto picks the truly fastest one. It takes a few minutes (the NPU compiles " +
                "the 3.4 GB model), keep the app open.",
        )
        ButtonRow {
            Button(onClick = vm::benchmarkUnet, enabled = !busy) { Text("Test the UNet on each chip") }
        }
        benchText?.let {
            Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 15.sp, modifier = Modifier.padding(12.dp))
            }
        }

        SectionTitle("NPU compile cache")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = cache, onCheckedChange = { cache = it; vm.npuCache = it })
            Spacer(Modifier.width(8.dp))
            Text("Save compiled NPU models (much faster start next time)")
        }
        ButtonRow {
            OutlinedButton(onClick = vm::clearNpuCache) { Text("Delete saved compiles") }
            OutlinedButton(onClick = { vm.releaseMemory() }) { Text("Free memory") }
        }

        SectionTitle("Log")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp).heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(log.takeLast(80).joinToString("\n").ifEmpty { "Nothing yet" },
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
        ButtonRow {
            OutlinedButton(onClick = { copyToClipboard(context, "App log", log.joinToString("\n")) }) { Text("Copy log") }
            OutlinedButton(onClick = { scope.launch { systemLog = vm.systemLog() } }) { Text("Detailed system log") }
        }
        Spacer(Modifier.height(24.dp))
    }

    systemLog?.let { text ->
        AlertDialog(
            onDismissRequest = { systemLog = null },
            title = { Text("System log (this app only)") },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    Text(text.takeLast(30_000), fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp,
                        modifier = Modifier.horizontalScroll(rememberScrollState()))
                }
            },
            confirmButton = { TextButton(onClick = { copyToClipboard(context, "System log", text) }) { Text("Copy") } },
            dismissButton = { TextButton(onClick = { systemLog = null }) { Text("Close") } },
        )
    }
}
