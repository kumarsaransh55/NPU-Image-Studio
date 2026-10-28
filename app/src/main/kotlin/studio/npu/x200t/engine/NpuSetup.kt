package studio.npu.x200t.engine

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtEpDevice
import ai.onnxruntime.OrtHardwareDevice
import ai.onnxruntime.OrtProvider
import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Log
import studio.npu.x200t.BuildConfig
import java.io.File

/**
 * Connects ONNX Runtime to the MediaTek NPU:
 *  1. load MediaTek's Neuron runtime (vivo's copy from /vendor, or the one bundled in the "bundled" APK)
 *  2. register MediaTek's ONNX Runtime NeuroPilot plugin (libonnxruntime_providers_neuropilot.so)
 *  3. ask ONNX Runtime which NPU devices the plugin found
 */
object NpuSetup {
    private const val TAG = "NPUStudio"
    const val PLUGIN_LIB = "libonnxruntime_providers_neuropilot.so"
    private const val ADAPTER_LIB = "libneuronusdk_adapter.mtk.so"

    data class Status(
        val device: String,
        val soc: String,
        val android: String,
        val pageSizeKb: Long,
        val runtimeSource: String?,
        val runtimeError: String?,
        val pluginRegistered: Boolean,
        val pluginError: String?,
        val npuDevices: List<OrtEpDevice>,
        val allDevices: List<String>,
        val gpuAvailable: Boolean,
        val nnapiAvailable: Boolean,
        val vendorLibsInfo: String,
        val ortVersion: String,
    ) {
        val npuReady: Boolean get() = pluginRegistered && npuDevices.isNotEmpty()

        fun summary(): String = when {
            npuReady -> "NPU ready (${npuDevices.joinToString { it.epName + "/" + it.device.type }})"
            runtimeError != null && !pluginRegistered -> "NPU not available: $runtimeError"
            pluginError != null -> "NPU plugin failed: $pluginError"
            else -> "NPU plugin loaded but found no NPU device"
        }

        fun report(): String = buildString {
            appendLine("Device: $device")
            appendLine("SoC: $soc")
            appendLine("Android: $android, page size ${pageSizeKb} KB")
            appendLine("ONNX Runtime: $ortVersion")
            appendLine("APK flavor: ${if (BuildConfig.BUNDLED_NEURON_RUNTIME) "bundled MediaTek runtime" else "system (vivo) runtime"}")
            appendLine("Neuron runtime: ${runtimeSource ?: "not loaded"}${runtimeError?.let { " - $it" } ?: ""}")
            appendLine("NeuroPilot plugin: ${if (pluginRegistered) "registered" else "not registered"}${pluginError?.let { " - $it" } ?: ""}")
            appendLine("NPU devices: ${if (npuDevices.isEmpty()) "none" else npuDevices.joinToString { "${it.epName} (${it.device.type}, ${it.device.vendor})" }}")
            appendLine("All ONNX Runtime devices: ${allDevices.joinToString()}")
            appendLine("GPU (WebGPU/Vulkan) provider: ${if (gpuAvailable) "available" else "missing"}")
            appendLine("NNAPI provider: ${if (nnapiAvailable) "available" else "missing"}")
            appendLine("Vendor public libraries: $vendorLibsInfo")
        }
    }

    @Volatile
    var status: Status? = null
        private set

    /** [skipPlugin] = the NPU crashed the app before, so don't touch it (the user can re-enable it). */
    @Synchronized
    fun init(context: Context, env: OrtEnvironment, skipPlugin: Boolean): Status {
        status?.let { return it }
        val libDir = File(context.applicationInfo.nativeLibraryDir)

        var runtimeSource: String? = null
        var runtimeError: String? = null
        var registered = false
        var pluginError: String? = null
        if (skipPlugin) {
            runtimeError = "turned off because it crashed the app last time"
            pluginError = runtimeError
        } else {
            // 1) Neuron runtime
            try {
                System.loadLibrary("neuronusdk_adapter.mtk")
                runtimeSource = if (File(libDir, ADAPTER_LIB).isFile) "MediaTek public runtime bundled in this APK"
                else "vivo system runtime (/vendor)"
            } catch (e: Throwable) {
                runtimeError = "can't open $ADAPTER_LIB (${e.message?.take(160)})"
                Log.w(TAG, "Neuron runtime load failed", e)
            }

            // 2) NeuroPilot plugin for ONNX Runtime (only useful if the runtime loaded)
            val plugin = File(libDir, PLUGIN_LIB)
            // Plugin log detail (our patched build logs to logcat with tag "NeuronEP"):
            // 1 = info (default), 0 = every node. Developer switch: create files/npu_debug.txt over adb.
            val debug = File(context.getExternalFilesDir(null), "npu_debug.txt").isFile
            runCatching { Os.setenv("NEURON_EP_LOG_LEVEL", if (debug) "0" else "1", true) }
            // The plugin also writes its log to this file (vivo rate-limits busy apps in logcat).
            val pluginLog = File(context.getExternalFilesDir(null), "npu_plugin_log.txt")
            runCatching {
                if (pluginLog.isFile) pluginLog.renameTo(File(pluginLog.parentFile, "npu_plugin_log_prev.txt"))
                Os.setenv("NEURON_EP_LOG_FILE", pluginLog.absolutePath, true)
            }
            if (debug) Log.i(TAG, "NeuroPilot plugin: detailed logging on")
            if (runtimeError == null) {
                try {
                    System.loadLibrary("neuron_op_capability")
                    if (!plugin.isFile) error("$PLUGIN_LIB missing from the APK")
                    env.registerExecutionProviderLibrary("NeuroPilot", plugin.absolutePath)
                    registered = true
                } catch (e: Throwable) {
                    pluginError = e.message?.lineSequence()?.firstOrNull()?.take(200) ?: e.javaClass.simpleName
                    Log.w(TAG, "NeuroPilot plugin registration failed", e)
                }
            } else {
                pluginError = "skipped (no Neuron runtime)"
            }
        }

        // 3) Devices
        val all = runCatching { env.epDevices }.getOrElse { emptyList() }
        val npu = all.filter { d ->
            val n = (d.epName + " " + d.epVendor).lowercase()
            ("neuron" in n || "mediatek" in n || "neuropilot" in n) && d.epName != "CPUExecutionProvider"
        }.sortedByDescending { if (it.device.type == OrtHardwareDevice.OrtHardwareDeviceType.NPU) 1 else 0 }

        val providers = runCatching { OrtEnvironment.getAvailableProviders() }.getOrElse { java.util.EnumSet.noneOf(OrtProvider::class.java) }
        val s = Status(
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            soc = "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL} (${Build.HARDWARE})",
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            pageSizeKb = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) / 1024 }.getOrDefault(4),
            runtimeSource = runtimeSource,
            runtimeError = runtimeError,
            pluginRegistered = registered,
            pluginError = pluginError,
            npuDevices = npu,
            allDevices = all.map { "${it.epName}:${it.device.type}" },
            gpuAvailable = OrtProvider.WEBGPU in providers,
            nnapiAvailable = OrtProvider.NNAPI in providers,
            vendorLibsInfo = vendorPublicLibs(),
            ortVersion = env.version,
        )
        Log.i(TAG, s.report())
        status = s
        return s
    }

    /** Which NPU-related libraries vivo lists as "public" (readable on most phones). */
    private fun vendorPublicLibs(): String {
        val files = listOf("/vendor/etc/public.libraries.txt", "/system/etc/public.libraries.txt")
        val found = ArrayList<String>()
        var readable = false
        for (path in files) {
            val f = File(path)
            val lines = runCatching { f.readLines() }.getOrNull() ?: continue
            readable = true
            lines.map { it.trim().substringBefore(' ') }
                .filter { it.contains("neuron", true) || it.contains("apu", true) || it.contains("OpenCL", true) || it.contains("mvpu", true) }
                .forEach { found.add(it) }
        }
        return when {
            !readable -> "list not readable on this phone"
            found.isEmpty() -> "no NPU libraries listed as public"
            else -> found.distinct().joinToString()
        }
    }
}
