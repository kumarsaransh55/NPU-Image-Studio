package studio.npu.x200t.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import studio.npu.sd.GenerationListener
import studio.npu.sd.GenerationParams
import studio.npu.sd.RunControl
import studio.npu.x200t.R
import studio.npu.x200t.StudioApp
import studio.npu.x200t.media.MediaSaver
import studio.npu.x200t.ui.MainActivity
import studio.npu.x200t.ui.toBitmap

/**
 * Keeps the app working while the screen is off: a foreground service (with a notification) plus a CPU
 * wake lock, held while an image, animation, NPU compile or speed test is running. Without it Android
 * freezes the app a few seconds after the phone is locked.
 *
 * It also accepts developer commands over USB (adb). The service is protected by the DUMP permission,
 * which only the adb shell has, so other apps can't start it:
 *   adb shell am start-foreground-service -n studio.npu.x200t/.engine.WorkService --es cmd benchmark_unet --ei size 512
 *   adb shell am start-foreground-service -n studio.npu.x200t/.engine.WorkService --es cmd selftest
 *   adb shell am start-foreground-service -n studio.npu.x200t/.engine.WorkService --es cmd generate --es prompt "a red fox" --ei size 512 --ei steps 4 --ez tiny true
 * Other commands: release (free memory), retryfailed (forget failed chips), reenable (undo the crash safety
 * switch), cacheinfo (list saved NPU compiles).
 * Results go to Android/data/studio.npu.x200t/files/app_log.txt.
 */
class WorkService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("Working on your image..."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NPUStudio:work").apply { acquire(60 * 60 * 1000L) }
        }
        val cmd = intent?.getStringExtra("cmd")
        if (cmd != null) runCommand(cmd, intent)
        if (intent?.getBooleanExtra("stop", false) == true && job?.isActive != true) stopSelf()
        return START_NOT_STICKY
    }

    private fun runCommand(cmd: String, intent: Intent) {
        val app = application as StudioApp
        val engine = app.engine
        if (job?.isActive == true) {
            engine.log("Dev command '$cmd' ignored: another one is still running")
            return
        }
        engine.log("Dev command: $cmd")
        job = app.appScope.launch {
            try {
                if (cmd != "reenable") engine.initNpu()
                when (cmd) {
                    "selftest" -> engine.selfTest()
                    "benchmark_unet" -> {
                        val m = app.store.installed().firstOrNull() ?: error("no model installed")
                        val size = intent.getIntExtra("size", 512)
                        engine.benchmarkUnet(m, size, size) { engine.log(it) }
                    }
                    "generate" -> {
                        val m = app.store.installed().firstOrNull() ?: error("no model installed")
                        val size = intent.getIntExtra("size", 512)
                        val p = GenerationParams(
                            prompt = intent.getStringExtra("prompt") ?: "a cozy cabin in a snowy forest at night",
                            width = size, height = size,
                            steps = intent.getIntExtra("steps", 4),
                            guidance = 8f,
                            seed = intent.getLongExtra("seed", 42L),
                            tinyDecoder = intent.getBooleanExtra("tiny", true),
                        )
                        val ctl = RunControl()
                        val img = engine.onEngine {
                            engine.pipelineFor(m).generate(p, ctl, object : GenerationListener {
                                override fun onStatus(text: String) = engine.log("  $text")
                            })
                        }
                        ctl.close()
                        val s = img.stats
                        engine.log("Dev generate done in ${s?.totalMs} ms: UNet ${s?.unetCalls} x ${s?.msPerUnetCall} ms, " +
                            "decoder ${s?.decodeMs} ms, text ${s?.textMs} ms, on ${s?.backends}")
                        MediaSaver.saveImage(this@WorkService, img.toBitmap(), "npu_dev_${System.currentTimeMillis()}")
                        engine.log("Dev image saved to Pictures/NPU Image Studio")
                    }
                    "release" -> engine.releaseMemory()
                    "retryfailed" -> engine.retryFailed()
                    "cacheinfo" -> java.io.File(filesDir, "npu_cache").listFiles()?.sortedBy { it.name }
                        ?.forEach { engine.log("  cache ${it.name}: ${it.length() / 1024} KB") }
                    // Undo the crash safety switch (e.g. after a test was interrupted by reinstalling the app).
                    // Takes effect after the app is restarted.
                    "reenable" -> engine.disabled.value.keys.forEach { engine.reEnable(it) }
                    else -> engine.log("Unknown dev command '$cmd'")
                }
            } catch (e: Throwable) {
                engine.log("Dev command '$cmd' failed: ${e.message}")
            } finally {
                engine.log("Dev command '$cmd' finished")
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        runCatching { wakeLock?.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Background work", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("NPU Image Studio")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "work"
        private const val NOTIFICATION_ID = 1

        /** Called by the app when it starts/stops long work, so it keeps running with the screen off. */
        fun keepAlive(context: Context, on: Boolean) {
            val i = Intent(context, WorkService::class.java)
            if (on) {
                runCatching { context.startForegroundService(i) }
            } else {
                runCatching { context.stopService(i) }
            }
        }
    }
}
