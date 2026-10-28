package studio.npu.x200t

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import studio.npu.x200t.engine.Engine
import studio.npu.x200t.models.Downloads
import studio.npu.x200t.models.ModelStore

class StudioApp : Application() {
    lateinit var engine: Engine
        private set
    lateinit var store: ModelStore
        private set

    /** Lives as long as the app process, so downloads keep going when you leave the screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var downloads: Downloads
        private set

    override fun onCreate() {
        super.onCreate()
        engine = Engine(this)
        store = ModelStore(this)
        downloads = Downloads(store, appScope, engine::log)
    }
}
