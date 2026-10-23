package org.funkgateway.mobile.frn

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class FrnSettings(
    val email: String = "",
    val password: String = "",
    val callsign: String = "",
    val description: String = "FunkGateway Mobile",
    val country: String = "Germany",
    val city: String = "",
    val vx: String = "20261016",
    val clientType: String = "2",
    val bandChannel: String = "PC Only"
)

object FrnSettingsStore {
    private const val PREFS = "frn_connection_settings"
    private const val KEY_ALIAS = "funkgateway_mobile_frn_password"

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
        }
        val existing = store.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        if (value.isBlank()) return ""
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 28) { "Ungültige verschlüsselte FRN-Daten." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, bytes.copyOfRange(0, 12))
        )
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    fun load(context: Context): FrnSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return FrnSettings(
            email = prefs.getString("email", "") ?: "",
            password = decrypt(prefs.getString("password", "") ?: ""),
            callsign = prefs.getString("callsign", "") ?: "",
            description = prefs.getString("description", "FunkGateway Mobile") ?: "",
            country = prefs.getString("country", "Germany") ?: "",
            city = prefs.getString("city", "") ?: "",
            vx = prefs.getString("vx", "20261016") ?: "",
            clientType = prefs.getString("clientType", "2") ?: "",
            bandChannel = prefs.getString("bandChannel", "PC Only") ?: ""
        )
    }

    fun save(context: Context, settings: FrnSettings) {
        val encryptedPassword = encrypt(settings.password)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        check(
            prefs.edit()
                .putString("email", settings.email)
                .putString("password", encryptedPassword)
                .putString("callsign", settings.callsign)
                .putString("description", settings.description)
                .putString("country", settings.country)
                .putString("city", settings.city)
                .putString("vx", settings.vx)
                .putString("clientType", settings.clientType)
                .putString("bandChannel", settings.bandChannel)
                .commit()
        ) { "FRN-Einstellungen konnten nicht gespeichert werden." }
    }
}
