package studio.npu.x200t.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnimateScreen(vm: StudioViewModel, modifier: Modifier) {
    val st by vm.anim.collectAsStateWithLifecycle()
    val create by vm.create.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Make a short video", style = MaterialTheme.typography.titleLarge)
        Hint(
            "Phones can't yet run real text-to-video models offline. This makes a smooth morph animation instead: " +
                "it generates every frame (on the NPU when available) while blending from the first prompt to the second, " +
                "then saves an MP4. Size, steps, seed and decoder come from the Create tab.",
        )
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(value = st.promptA, onValueChange = { v -> vm.updateAnim { it.copy(promptA = v) } },
            label = { Text("Start: describe the first scene") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = st.promptB, onValueChange = { v -> vm.updateAnim { it.copy(promptB = v) } },
            label = { Text("End: describe the last scene (empty = same prompt, new seed)") }, minLines = 2,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp))

        Text("Frames to generate: ${st.frames}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
        Slider(value = st.frames.toFloat(), onValueChange = { v -> vm.updateAnim { it.copy(frames = v.roundToInt()) } },
            valueRange = 4f..48f, steps = 43)
        Text("Playback speed", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(6, 8, 12, 16, 24).forEach { f ->
                FilterChip(selected = st.fps == f, onClick = { vm.updateAnim { it.copy(fps = f) } }, label = { Text("$f fps") })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Switch(checked = st.pingPong, onCheckedChange = { v -> vm.updateAnim { it.copy(pingPong = v) } })
            Spacer(Modifier.width(8.dp))
            Text("Loop back to the start (doubles the length for free)")
        }
        val seconds = (if (st.pingPong) st.frames * 2 - 2 else st.frames).toFloat() / st.fps
        Hint("Video length ≈ ${"%.1f".format(seconds)} s at ${create.width}×${create.height}, ${create.steps} steps per frame.")

        Spacer(Modifier.height(10.dp))
        AnimatedFrames(st)
        if (st.busy) LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        if (st.status.isNotEmpty()) Hint(st.status)
        st.error?.let { ErrorText(it) }

        ButtonRow {
            if (st.busy) {
                Button(onClick = vm::stopAnim, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Close, null); Spacer(Modifier.width(6.dp)); Text("Stop")
                }
            } else {
                Button(onClick = vm::animate, modifier = Modifier.weight(1f)) { Text("Make video") }
            }
            FilledTonalButton(onClick = vm::openVideo, enabled = st.videoUri != null) {
                Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Play")
            }
            IconButton(onClick = vm::shareVideo, enabled = st.videoUri != null) { Icon(Icons.Filled.Share, "Share") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Plays the frames generated so far in a loop. */
@Composable
private fun AnimatedFrames(st: AnimState) {
    val frames = st.frameBitmaps
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(frames.size, st.fps, st.pingPong) {
        if (frames.size < 2) return@LaunchedEffect
        val order = if (st.pingPong) frames.indices + frames.indices.reversed().drop(1).dropLast(1) else frames.indices.toList()
        var k = 0
        while (true) {
            index = order[k % order.size]
            k++
            delay(1000L / st.fps)
        }
    }
    val bmp = frames.getOrNull(index.coerceAtMost(frames.size - 1)) ?: frames.lastOrNull()
    ImageArea(bmp, bmp?.width ?: 512, bmp?.height ?: 512, if (st.busy) "Generating the first frame..." else "Your animation appears here")
}
