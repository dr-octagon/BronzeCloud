package com.keyiflerolsun

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

@CloudstreamPlugin
class VavooPlugin : Plugin() {
    private val defaultCountries = linkedMapOf(
        "Turkey" to true,
        "Germany" to false,
        "Albania" to false,
        "France" to false,
        "Balkans" to false,
        "Portugal" to false,
        "Poland" to false,
        "Italy" to false,
        "United Kingdom" to false,
        "Romania" to false,
        "Arabia" to false,
        "Russia" to false,
        "Spain" to false,
        "Bulgaria" to false,
        "Netherlands" to false,
    )

    private val countriesToLanguage = mapOf(
        "Germany" to "de",
        "Albania" to "al",
        "France" to "fr",
        "Balkans" to "",
        "Turkey" to "tr",
        "Portugal" to "pt",
        "Poland" to "pl",
        "Italy" to "it",
        "United Kingdom" to "uk",
        "Romania" to "ro",
        "Arabia" to "sa",
        "Russia" to "ru",
        "Spain" to "es",
        "Bulgaria" to "bg",
        "Netherlands" to "nl",
    )

    override fun load(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val savedCountries = preferences.getString(COUNTRIES_KEY, null)
            ?.let { runCatching { parseJson<Map<String, Boolean>>(it) }.getOrNull() }
        val countries = defaultCountries.mapValues { (country, enabledByDefault) ->
            savedCountries?.get(country) ?: enabledByDefault
        }
        val enabledCountries = countries.filterValues { it }
        val language = enabledCountries.keys.singleOrNull()?.let(countriesToLanguage::get).orEmpty()

        registerMainAPI(Vavoo(countries, language, preferences))

        openSettings = { settingsContext ->
            showCountrySettings(settingsContext, preferences, countries)
        }
    }

    private fun showCountrySettings(
        context: Context,
        preferences: android.content.SharedPreferences,
        currentCountries: Map<String, Boolean>,
    ) {
        val names = currentCountries.keys.sorted().toTypedArray()
        val selected = BooleanArray(names.size) { currentCountries[names[it]] == true }

        AlertDialog.Builder(context)
            .setTitle("Vavoo ülkeleri")
            .setMultiChoiceItems(names, selected) { _, index, checked ->
                selected[index] = checked
            }
            .setNegativeButton("İptal", null)
            .setPositiveButton("Kaydet") { _, _ ->
                if (selected.none { it }) {
                    Toast.makeText(context, "En az bir ülke seçmelisiniz.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                val updatedCountries = names.indices.associate { names[it] to selected[it] }
                preferences.edit().putString(COUNTRIES_KEY, updatedCountries.toJson()).apply()
                Toast.makeText(
                    context,
                    "Kaydedildi. Değişiklikler için Cloudstream'i yeniden başlatın.",
                    Toast.LENGTH_LONG,
                ).show()
            }
            .show()
    }

    private companion object {
        const val PREFERENCES_NAME = "Vavoo"
        const val COUNTRIES_KEY = "countries"
    }
}
