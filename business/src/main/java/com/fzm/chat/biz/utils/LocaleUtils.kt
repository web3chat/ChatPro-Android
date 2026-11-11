package com.fzm.chat.biz.utils

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * 多语言切换工具类
 *
 * 支持的语言与 TokenPocket App 保持一致（共 15 种）：
 * 简体中文、繁體中文、English、日本語、한국어、Deutsch、Français、Español、
 * Português、Italiano、Русский、ไทย、Tiếng Việt、Bahasa Indonesia、Türkçe
 */
object LocaleUtils {

    private const val SP_NAME = "locale_config"
    private const val KEY_LANGUAGE = "language"

    // 语言代码常量（与 values-xx 资源目录一一对应）
    const val LANG_CHINESE = "zh"               // 简体中文
    const val LANG_TRADITIONAL = "zh-rTW"       // 繁體中文
    const val LANG_ENGLISH = "en"               // English
    const val LANG_JAPANESE = "ja"              // 日本語
    const val LANG_KOREAN = "ko"                // 한국어
    const val LANG_GERMAN = "de"                // Deutsch
    const val LANG_FRENCH = "fr"                // Français
    const val LANG_SPANISH = "es"               // Español
    const val LANG_PORTUGUESE = "pt"            // Português
    const val LANG_ITALIAN = "it"               // Italiano
    const val LANG_RUSSIAN = "ru"               // Русский
    const val LANG_THAI = "th"                  // ไทย
    const val LANG_VIETNAMESE = "vi"            // Tiếng Việt
    const val LANG_INDONESIAN = "in"            // Bahasa Indonesia（Android 资源限定符为 in）
    const val LANG_TURKISH = "tr"               // Türkçe

    /**
     * 支持的语言列表（语言代码 -> 语言原生名称），顺序即语言选择页展示顺序
     */
    val supportedLanguages: LinkedHashMap<String, String> = linkedMapOf(
        LANG_CHINESE to "简体中文",
        LANG_TRADITIONAL to "繁體中文",
        LANG_ENGLISH to "English",
        LANG_JAPANESE to "日本語",
        LANG_KOREAN to "한국어",
        LANG_GERMAN to "Deutsch",
        LANG_FRENCH to "Français",
        LANG_SPANISH to "Español",
        LANG_PORTUGUESE to "Português",
        LANG_ITALIAN to "Italiano",
        LANG_RUSSIAN to "Русский",
        LANG_THAI to "ไทย",
        LANG_VIETNAMESE to "Tiếng Việt",
        LANG_INDONESIAN to "Bahasa Indonesia",
        LANG_TURKISH to "Türkçe"
    )

    /**
     * 每次切换语言时递增，用于检测语言是否发生变化
     */
    @Volatile
    var changeCount: Int = 0
        private set

    /**
     * 获取当前保存的语言设置
     */
    fun getSavedLanguage(context: Context): String {
        val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        return sp.getString(KEY_LANGUAGE, LANG_CHINESE) ?: LANG_CHINESE
    }

    /**
     * 保存语言设置
     */
    fun saveLanguage(context: Context, language: String) {
        val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_LANGUAGE, language).apply()
    }

    /**
     * 获取当前显示语言的原生名称
     */
    fun getCurrentLanguageName(context: Context): String {
        return supportedLanguages[getSavedLanguage(context)] ?: "简体中文"
    }

    /**
     * 切换语言并更新配置
     */
    fun changeLanguage(context: Context, language: String): Context {
        saveLanguage(context, language)
        changeCount++
        return updateConfiguration(context, language)
    }

    /**
     * 应用语言设置（通常在 Application 或 BaseActivity 中调用）
     */
    fun applyLanguage(context: Context): Context {
        val language = getSavedLanguage(context)
        return updateConfiguration(context, language)
    }

    /**
     * 将语言代码转换为 Locale
     */
    private fun toLocale(language: String): Locale {
        return when (language) {
            LANG_TRADITIONAL -> Locale.TRADITIONAL_CHINESE
            LANG_ENGLISH -> Locale.ENGLISH
            LANG_JAPANESE -> Locale.JAPANESE
            LANG_KOREAN -> Locale.KOREAN
            LANG_GERMAN -> Locale.GERMAN
            LANG_FRENCH -> Locale.FRENCH
            LANG_SPANISH -> Locale("es")
            LANG_PORTUGUESE -> Locale("pt")
            LANG_ITALIAN -> Locale.ITALIAN
            LANG_RUSSIAN -> Locale("ru")
            LANG_THAI -> Locale("th")
            LANG_VIETNAMESE -> Locale("vi")
            LANG_INDONESIAN -> Locale("in", "ID")
            LANG_TURKISH -> Locale("tr")
            else -> Locale.SIMPLIFIED_CHINESE
        }
    }

    private fun updateConfiguration(context: Context, language: String): Context {
        val locale = toLocale(language)
        Locale.setDefault(locale)

        val resources: Resources = context.resources
        val configuration: Configuration = resources.configuration

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocale(locale)
            configuration.setLocales(LocaleList(locale))
            return context.createConfigurationContext(configuration)
        } else {
            @Suppress("DEPRECATION")
            configuration.locale = locale
            resources.updateConfiguration(configuration, resources.displayMetrics)
            return context
        }
    }

    /**
     * 检查是否为英文
     */
    fun isEnglish(context: Context): Boolean {
        return getSavedLanguage(context) == LANG_ENGLISH
    }

    /**
     * 检查是否为简体中文
     */
    fun isChinese(context: Context): Boolean {
        return getSavedLanguage(context) == LANG_CHINESE
    }
}
