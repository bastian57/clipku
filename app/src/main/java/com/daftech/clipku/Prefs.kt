package com.daftech.clipku

import android.content.Context
import android.content.SharedPreferences

/**
 * Penyimpanan lokal privat.
 *
 * Groq API key HANYA disimpan di sini (SharedPreferences privat app),
 * diisi user sendiri di Settings. Key TIDAK pernah di-hardcode di source,
 * TIDAK pernah di-log, dan DIKECUALIKAN dari backup (lihat backup_rules
 * dan data_extraction_rules) supaya tidak ikut tersalin ke cloud.
 */
class Prefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("clipku_prefs", Context.MODE_PRIVATE)

    var groqApiKey: String
        get() = prefs.getString("groq_api_key", "") ?: ""
        set(value) = prefs.edit().putString("groq_api_key", value).apply()

    fun clearGroqKey() {
        prefs.edit().remove("groq_api_key").apply()
    }
}
