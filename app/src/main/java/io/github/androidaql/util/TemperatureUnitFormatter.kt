package io.github.androidaql.util

import android.content.Context
import android.icu.util.LocaleData
import android.icu.util.ULocale
import java.util.Locale

enum class TempUnitPreference(val displayName: String) {
    AUTO_DEVICE("Auto (Follow Device Settings)"),
    FAHRENHEIT("Fahrenheit (°F)"),
    CELSIUS("Celsius (°C)")
}

/**
 * Honors the Android device's configured Regional Preferences / Locale temperature unit
 * (Unicode `-u-mu-fahrenhe` / `-u-mu-celsius` extension and ICU `LocaleData.MeasurementSystem`,
 * defaulting to °F for US locales) with an optional user override in Settings.
 */
object TemperatureUnitFormatter {

    private const val PREFS_NAME = "aql_user_prefs"
    private const val KEY_TEMP_UNIT = "temp_unit_pref"

    fun getPreference(context: Context): TempUnitPreference {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_TEMP_UNIT, TempUnitPreference.AUTO_DEVICE.name) ?: TempUnitPreference.AUTO_DEVICE.name
        return runCatching { TempUnitPreference.valueOf(raw) }.getOrDefault(TempUnitPreference.AUTO_DEVICE)
    }

    fun setPreference(context: Context, pref: TempUnitPreference) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TEMP_UNIT, pref.name)
            .apply()
    }

    fun useFahrenheit(context: Context?): Boolean {
        val pref = if (context != null) getPreference(context) else TempUnitPreference.AUTO_DEVICE
        return when (pref) {
            TempUnitPreference.FAHRENHEIT -> true
            TempUnitPreference.CELSIUS -> false
            TempUnitPreference.AUTO_DEVICE -> {
                val locale = context?.resources?.configuration?.locales?.get(0) ?: Locale.getDefault()
                // 1. Check Android 14+ Regional Preferences Unicode extension (-u-mu-fahrenhe / -u-mu-celsius)
                val muExt = runCatching { locale.getUnicodeLocaleType("mu")?.lowercase(Locale.US) }.getOrNull()
                when {
                    muExt != null && muExt.startsWith("fahren") -> true
                    muExt != null && muExt.startsWith("celsi") -> false
                    else -> {
                        // 2. Check ICU MeasurementSystem or country fallback
                        val isUsSystem = runCatching {
                            LocaleData.getMeasurementSystem(ULocale.forLocale(locale)) == LocaleData.MeasurementSystem.US
                        }.getOrDefault(false)
                        isUsSystem || locale.country.uppercase(Locale.US) in setOf("US", "LR", "MM")
                    }
                }
            }
        }
    }

    fun convertFromCelsius(tempC: Float, useFahrenheit: Boolean): Float =
        if (useFahrenheit) (tempC * 9.0f / 5.0f) + 32.0f else tempC

    fun unitSymbol(useFahrenheit: Boolean): String = if (useFahrenheit) "°F" else "°C"

    fun format(tempC: Float, useFahrenheit: Boolean, decimals: Int = 1): String {
        val v = convertFromCelsius(tempC, useFahrenheit)
        return String.format(Locale.US, "%.${decimals}f%s", v, unitSymbol(useFahrenheit))
    }
}
