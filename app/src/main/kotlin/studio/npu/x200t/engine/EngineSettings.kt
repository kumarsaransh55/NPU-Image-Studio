package studio.npu.x200t.engine

import android.content.Context
import androidx.core.content.edit
import studio.npu.sd.Component
import studio.npu.sd.FailureMemory

/** Failed (part, chip, model, size) combos, kept across app restarts. */
class PrefsFailureMemory(context: Context) : FailureMemory {
    private val prefs = context.getSharedPreferences("failures", Context.MODE_PRIVATE)
    override fun reason(key: String): String? = prefs.getString(key, null)
    override fun remember(key: String, reason: String) = prefs.edit { putString(key, reason) }
    override fun clear() = prefs.edit { clear() }

    /** "UNet (main model) on NPU (NeuroPilot)" -> reason, for the Engine screen. */
    fun describe(): List<Pair<String, String>> = prefs.all.mapNotNull { (k, v) ->
        val parts = k.split('|')
        if (parts.size < 2) return@mapNotNull null
        val comp = runCatching { Component.valueOf(parts[0]).label }.getOrDefault(parts[0])
        "$comp on ${backendName(parts[1])}" to (v as? String ?: "")
    }.sortedBy { it.first }
}

/** Small wrapper around SharedPreferences for everything the user can change. */
class EngineSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun accel(c: Component): Accel =
        runCatching { Accel.valueOf(prefs.getString("accel_${c.name}", Accel.AUTO.name)!!) }.getOrDefault(Accel.AUTO)

    fun setAccel(c: Component, a: Accel) = prefs.edit { putString("accel_${c.name}", a.name) }

    var npuCache: Boolean
        get() = prefs.getBoolean("npu_cache", true)
        set(v) = prefs.edit { putBoolean("npu_cache", v) }

    /**
     * true = when you pick a specific chip (e.g. NNAPI) and it fails, stop with the error instead of
     * quietly switching to the CPU. Handy to see exactly what is wrong.
     */
    var strict: Boolean
        get() = prefs.getBoolean("strict", false)
        set(v) = prefs.edit { putBoolean("strict", v) }

    /** Speed test results in ms (-1 = failed). kind = "tiny" (TAESD self-test) or "unet" (UNet benchmark). */
    fun bench(kind: String): Map<String, Long> =
        listOf("npu", "nnapi", "gpu", "cpu").mapNotNull { id ->
            val v = prefs.getLong("bench_${kind}_$id", Long.MIN_VALUE)
            if (v == Long.MIN_VALUE) null else id to v
        }.toMap()

    fun setBench(kind: String, results: Map<String, Long>) = prefs.edit {
        listOf("npu", "nnapi", "gpu", "cpu").forEach { remove("bench_${kind}_$it") }
        results.forEach { (id, ms) -> putLong("bench_${kind}_$id", ms) }
    }

    var benchVersion: Int
        get() = prefs.getInt("bench_version", 0)
        set(v) = prefs.edit { putInt("bench_version", v) }

    /** Accelerators switched off after they crashed the app: backend id -> what it was doing. */
    fun disabledAccels(): Map<String, String> =
        (prefs.getStringSet("disabled_accels", emptySet()) ?: emptySet())
            .associate { it.substringBefore('|') to it.substringAfter('|', "") }

    fun disableAccel(id: String, reason: String) {
        val rest = disabledAccels().filterKeys { it != id }.map { "${it.key}|${it.value}" }
        prefs.edit { putStringSet("disabled_accels", (rest + "$id|$reason").toSet()) }
    }

    fun enableAccel(id: String) {
        val rest = disabledAccels().filterKeys { it != id }.map { "${it.key}|${it.value}" }
        prefs.edit { putStringSet("disabled_accels", rest.toSet()) }
    }

    fun getString(key: String, def: String): String = prefs.getString(key, def) ?: def
    fun putString(key: String, v: String) = prefs.edit { putString(key, v) }
    fun getInt(key: String, def: Int): Int = prefs.getInt(key, def)
    fun putInt(key: String, v: Int) = prefs.edit { putInt(key, v) }
    fun getFloat(key: String, def: Float): Float = prefs.getFloat(key, def)
    fun putFloat(key: String, v: Float) = prefs.edit { putFloat(key, v) }
    fun getBool(key: String, def: Boolean): Boolean = prefs.getBoolean(key, def)
    fun putBool(key: String, v: Boolean) = prefs.edit { putBoolean(key, v) }
    fun getLong(key: String, def: Long): Long = prefs.getLong(key, def)
    fun putLong(key: String, v: Long) = prefs.edit { putLong(key, v) }
}
