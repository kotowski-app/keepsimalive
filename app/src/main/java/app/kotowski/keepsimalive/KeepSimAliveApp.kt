package app.kotowski.keepsimalive

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.kotowski.keepsimalive.util.applyStoredAppLanguage
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class KeepSimAliveApp :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        if (isUnitTestEnvironment) {
            return
        }
        // A fresh background process starts with the AppCompat app-language state empty
        // (below API 33 it is process-local), so restore the persisted pick before the
        // workers below resolve any user-facing strings.
        applyStoredAppLanguage()
        app.kotowski.keepsimalive.work.ScheduleSafetyWorker
            .enqueuePeriodic(this)
        app.kotowski.keepsimalive.work.ScheduleSafetyWorker
            .enqueueOnce(this)
    }

    // The single WorkManager configuration: WorkManager picks it up lazily on the first
    // getInstance() (the enqueue calls above trigger it), since the manifest removes the
    // androidx.startup initializer and no explicit initialize() is called.
    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()

    private val isUnitTestEnvironment: Boolean
        get() = runCatching { Class.forName("org.robolectric.RuntimeEnvironment") }.isSuccess
}
