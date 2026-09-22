package app.kotowski.keepsimalive.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

// A copy of this context configured with the app language the user picked in Settings, or
// this context itself while the app follows the system language. Needed because below API
// 33 the platform applies the per-app language to activity contexts only: everything that
// resolves user-facing strings or date formats from the application context (ViewModels,
// workers, notifications) would otherwise render in the system language. On API 33+ the
// platform applies the per-app language process-wide, so the copy is equivalent there.
//
// The pick is read from AppCompat's process-local static, which a fresh background process
// below API 33 starts empty until the first activity attach. While the static is empty the
// persisted tag (AppPrefs.appLanguageTag, re-applied at process start) covers that window;
// an empty tag keeps returning the context unchanged.
fun Context.appLocaleContext(): Context {
    val locales = AppCompatDelegate.getApplicationLocales()
    if (!locales.isEmpty) return configurationContext(locales.toLanguageTags())
    val tag = AppPrefs(this).appLanguageTag
    if (tag.isEmpty()) return this
    return configurationContext(tag)
}

// The one Configuration copy both non-empty paths share.
private fun Context.configurationContext(tags: String): Context {
    val config = Configuration(resources.configuration)
    config.setLocales(LocaleList.forLanguageTags(tags))
    return createConfigurationContext(config)
}

// Restores the persisted app language pick into the AppCompat process-local state at
// process start (called from Application.onCreate): a fresh background process below API
// 33 starts with the state empty until the first activity attach, and without this
// re-apply the background strings would render in the system language. No-op from API 33
// (the platform owns the per-app locale process-wide there) and while the stored tag is
// empty.
internal fun Context.applyStoredAppLanguage() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
    val tag = AppPrefs(this).appLanguageTag
    if (tag.isEmpty()) return
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
}

// The sanctioned way to resolve user-facing text from an application context (see
// appLocaleContext): Compose code resolves through the activity context and needs no
// wrapping, everything below that layer goes through these two. The no-arg form calls the
// overload without format args on purpose: it keeps a stub of that overload live.
fun Context.appString(
    resId: Int,
    vararg args: Any,
): String {
    val ctx = appLocaleContext()
    return if (args.isEmpty()) {
        ctx.getString(resId)
    } else {
        ctx.getString(resId, *args)
    }
}

// Like appString, the no-arg form calls the overload without format args (the one a stub
// of getQuantityString without args targets).
fun Context.appQuantity(
    pluralResId: Int,
    quantity: Int,
    vararg args: Any,
): String {
    val resources = appLocaleContext().resources
    return if (args.isEmpty()) {
        resources.getQuantityString(pluralResId, quantity)
    } else {
        resources.getQuantityString(pluralResId, quantity, *args)
    }
}
