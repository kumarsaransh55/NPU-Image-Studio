package studio.npu.x200t.ui

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: StudioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Developer testing over USB: `adb shell am start -n studio.npu.x200t/.ui.MainActivity --ez dev_show_when_locked true`
        // shows the app above the lock screen (like an alarm) so long NPU tests run at full speed.
        if (intent?.getBooleanExtra("dev_show_when_locked", false) == true) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        enableEdgeToEdge()
        setContent { StudioTheme { StudioRoot(vm) } }

        // The "working on your image" notification (shown while generating with the screen off).
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // Keep the screen on while generating or downloading (the phone sleeping would pause the work).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(vm.engineBusy, vm.downloads) { busy, dl -> busy || dl.values.any { it.running } }
                    .collect { on ->
                        val devMode = intent?.getBooleanExtra("dev_show_when_locked", false) == true
                        if (on || devMode) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshInstalled() // picks up model folders copied over USB
    }
}

@Suppress("DEPRECATION")
@Composable
private fun StudioRoot(vm: StudioViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(if (vm.installed.value.isEmpty()) 2 else 0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Filled.Create, null) }, label = { Text("Create") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Filled.PlayArrow, null) }, label = { Text("Animate") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Filled.List, null) }, label = { Text("Models") })
                NavigationBarItem(selected = tab == 3, onClick = { tab = 3 }, icon = { Icon(Icons.Filled.Build, null) }, label = { Text("Engine") })
                NavigationBarItem(selected = tab == 4, onClick = { tab = 4 }, icon = { Icon(Icons.Filled.Info, null) }, label = { Text("Help") })
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (tab) {
            0 -> CreateScreen(vm, m, goToModels = { tab = 2 }, goToEngine = { tab = 3 })
            1 -> AnimateScreen(vm, m)
            2 -> ModelsScreen(vm, m)
            3 -> EngineScreen(vm, m)
            else -> HelpScreen(m)
        }
    }
}
