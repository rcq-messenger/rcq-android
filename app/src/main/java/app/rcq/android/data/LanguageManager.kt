package app.rcq.android.data

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Per-app language override — the Android analogue of the iOS
 * LanguageManager / AppLanguage. ICQ-era users expect a manual in-app
 * switcher, so we don't rely on the system locale alone. The picker lists
 * the same languages iOS offers; translations land incrementally, so a
 * not-yet-translated language falls back to the default (English) strings,
 * exactly like iOS.
 *
 * Mechanism: [wrap] applies the chosen locale to a Configuration context in
 * MainActivity.attachBaseContext (runs before onCreate), so every
 * `stringResource` / `getString` resolves against the right `values-<lang>`.
 * [set] persists the choice and recreates the activity to re-attach.
 */
object LanguageManager {
    data class Lang(val code: String, val englishName: String, val nativeName: String)

    /** Mirrors iOS AppLanguage.allCases (order + set). */
    val supported = listOf(
        Lang("en", "English", "English"),
        Lang("ru", "Russian", "Русский"),
        Lang("es", "Spanish", "Español"),
        Lang("pt", "Portuguese", "Português"),
        Lang("fr", "French", "Français"),
        Lang("de", "German", "Deutsch"),
        Lang("it", "Italian", "Italiano"),
        Lang("tr", "Turkish", "Türkçe"),
        Lang("pl", "Polish", "Polski"),
        Lang("uk", "Ukrainian", "Українська"),
        Lang("zh-Hans", "Chinese (Simplified)", "简体中文"),
        Lang("ja", "Japanese", "日本語"),
        Lang("ko", "Korean", "한국어"),
        Lang("ar", "Arabic", "العربية"),
        Lang("hi", "Hindi", "हिन्दी"),
    )

    /// ⚠ What we actually ship resources for. `supported` is the full menu the
    /// three clients agree on; a code only belongs here once `values-…` exists,
    /// because picking a language with no strings behind it changes the label
    /// and leaves the app in English, which reads as a bug rather than as a
    /// missing translation.
    val available: List<Lang> get() = supported.filter { it.code in SHIPPED }

    private val SHIPPED = setOf("en", "ru", "es", "pt", "tr", "uk", "zh-Hans")

    private const val PREFS = "rcq_lang"
    private const val KEY = "lang"

    private val _current = MutableStateFlow("en")
    /** Active language code (e.g. "en", "ru", "zh-Hans"). */
    val current: StateFlow<String> = _current.asStateFlow()

    fun init(context: Context) {
        _current.value = persistedCode(context) ?: deviceDefault(context)
    }

    /** Persist [code] and recreate [activity] so the new locale takes. */
    fun set(activity: Activity, code: String) {
        if (code == _current.value) return
        _current.value = code
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, code).apply()
        activity.recreate()
    }

    fun displayName(code: String): String =
        supported.firstOrNull { it.code == code }?.nativeName ?: code

    // ── locale plumbing (called from attachBaseContext) ──────────────
    fun persistedCode(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    /** Wrap [base] with the chosen (or persisted) locale. */
    fun wrap(base: Context, code: String? = persistedCode(base)): Context {
        val locale = localeFor(code ?: return base)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    private fun localeFor(code: String): Locale =
        if (code.contains('-')) Locale.forLanguageTag(code) else Locale(code)

    private fun deviceDefault(context: Context): String {
        val sys = context.resources.configuration.locales[0].language
        return supported.firstOrNull { it.code == sys || it.code.startsWith(sys) }?.code ?: "en"
    }
}
