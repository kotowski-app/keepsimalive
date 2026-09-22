package app.kotowski.keepsimalive

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KeepSimAliveAppTest {
    @Test
    fun `onCreate skips WorkManager initialization in the unit-test environment`() {
        // Robolectric puts org.robolectric.RuntimeEnvironment on the classpath, so the early
        // return must fire: without it the enqueues would initialize WorkManager via the
        // Configuration.Provider, and workManagerConfiguration would read the uninjected
        // Hilt workerFactory and throw.
        val app = KeepSimAliveApp()

        app.onCreate() // must not throw
    }
}
