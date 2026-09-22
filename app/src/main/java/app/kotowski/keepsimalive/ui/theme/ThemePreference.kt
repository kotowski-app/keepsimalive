package app.kotowski.keepsimalive.ui.theme

import app.kotowski.keepsimalive.util.AppPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

// The single source of truth of the theme mode, shared between the root (which renders the
// theme from it) and the Settings screen (which changes it): the root collects the flow, so
// a pick recomposes the whole app with the new palette right away, no restart.
@Singleton
class ThemePreference
    @Inject
    constructor(
        private val prefs: AppPrefs,
    ) {
        private val _mode = MutableStateFlow(prefs.themeMode)
        val mode: StateFlow<String> = _mode.asStateFlow()

        // Commits one of the three modes only (the dropdown offers nothing else).
        fun set(mode: String) {
            if (mode !in AppPrefs.THEME_MODES) return
            prefs.themeMode = mode
            _mode.update { mode }
        }
    }
