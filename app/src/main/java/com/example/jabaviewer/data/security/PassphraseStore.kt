package com.example.jabaviewer.data.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class PassphraseStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun getPassphrase(): String? = prefs.getString(KEY_PASSPHRASE, null)

    fun setPassphrase(value: String) {
        prefs.edit().putString(KEY_PASSPHRASE, value).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_PASSPHRASE).apply()
    }

    private companion object {
        private const val KEY_PASSPHRASE = "passphrase"
    }
}
