package org.funkgateway.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class MumbleSavedConnection(
    val host: String = "",
    val port: Int = 64738,
    val username: String = "",
    val password: String = ""
)

object MumbleConnectionStore {
    private const val PREFS = "mumble_saved_connection"
    private const val KEY_ALIAS = "funkgateway_mobile_mumble_password"

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
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.iv + cipher.doFinal(
            value.toByteArray(Charsets.UTF_8)
        )
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        if (value.isBlank()) return ""
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 28) {
            "Ungültige verschlüsselte Mumble-Zugangsdaten."
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, bytes.copyOfRange(0, 12))
        )
        return String(
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)),
            Charsets.UTF_8
        )
    }

    fun load(context: Context): MumbleSavedConnection {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return MumbleSavedConnection(
            host = prefs.getString("host", "") ?: "",
            port = prefs.getInt("port", 64738).coerceIn(1, 65535),
            username = prefs.getString("username", "") ?: "",
            password = decrypt(prefs.getString("password", "") ?: "")
        )
    }

    fun save(context: Context, connection: MumbleSavedConnection) {
        require(connection.port in 1..65535) {
            "Ungültiger Mumble-Port."
        }
        val encryptedPassword = encrypt(connection.password)
        check(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("host", connection.host.trim())
                .putInt("port", connection.port)
                .putString("username", connection.username.trim())
                .putString("password", encryptedPassword)
                .commit()
        ) {
            "Mumble-Verbindung konnte nicht gespeichert werden."
        }
    }
}
