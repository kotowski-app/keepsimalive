package app.kotowski.keepsimalive

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.kotowski.keepsimalive.util.AppConfig
import app.kotowski.keepsimalive.util.AppPrefs
import app.kotowski.keepsimalive.util.LogBuffer
import app.kotowski.keepsimalive.util.LogFileStore
import app.kotowski.keepsimalive.util.Logger
import app.kotowski.keepsimalive.util.applyStoredAppLanguage
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class KeepSimAliveApp :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        Logger.i("KeepSimAliveApp", "app started, version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        if (isUnitTestEnvironment) {
            return
        }
        // A fresh background process starts with the AppCompat app-language state empty
        // (below API 33 it is process-local), so restore the persisted pick before the
        // workers below resolve any user-facing strings.
        applyStoredAppLanguage()
        initLogging()
        app.kotowski.keepsimalive.work.ScheduleSafetyWorker
            .enqueuePeriodic(this)
        app.kotowski.keepsimalive.work.ScheduleSafetyWorker
            .enqueueOnce(this)
    }

    // Catching Throwable, not Exception: an OOM in the tail load is an Error and must
    // degrade the same way, or it would crash-loop every process start.
    private fun initLogging() {
        try {
            val prefs = AppPrefs(this)
            val logFile = File(filesDir, AppConfig.LOG_FILE_NAME)
            if (prefs.logToFileEnabled) {
                LogBuffer.init(LogFileStore(logFile))
            } else {
                logFile.delete()
            }
        } catch (e: Throwable) {
            Logger.w("KeepSimAliveApp", "Log persistence init failed, continuing without it: ${e.message}", e)
        }
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
