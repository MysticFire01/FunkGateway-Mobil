package org.funkgateway.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MumbleIdentityStore(private val context: Context) {
    private val keyAlias = "funkgateway_mumble_identity_aes_v1"
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }

    private fun secretKey(): SecretKey {
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private val identityDirectory by lazy {
        java.io.File(context.filesDir, "mumble-identities").apply {
            if (!exists()) check(mkdirs()) { "Zertifikatsordner konnte nicht erstellt werden" }
        }
    }

    data class IdentityInfo(
        val subject: String,
        val issuer: String,
        val validFrom: Long,
        val validUntil: Long,
        val fingerprint: String
    )

    fun repackagePkcs12(
        original: ByteArray,
        originalPassword: String,
        newPassword: String
    ): ByteArray {
        require(newPassword.length >= 12) {
            "Das Exportpasswort muss mindestens 12 Zeichen haben"
        }

        val source = KeyStore.getInstance("PKCS12")
        source.load(original.inputStream(), originalPassword.toCharArray())

        val target = KeyStore.getInstance("PKCS12")
        target.load(null, newPassword.toCharArray())

        var copied = 0

        val aliases = source.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()

            if (source.isKeyEntry(alias)) {
                val key = source.getKey(alias, originalPassword.toCharArray())
                val chain = source.getCertificateChain(alias)
                require(key is java.security.PrivateKey && !chain.isNullOrEmpty()) {
                    "Ungültiger privater Schlüssel oder Zertifikatskette"
                }

                target.setKeyEntry(
                    alias,
                    key,
                    newPassword.toCharArray(),
                    chain
                )
                copied++
            } else if (source.isCertificateEntry(alias)) {
                target.setCertificateEntry(alias, source.getCertificate(alias))
            }
        }

        require(copied > 0) {
            "Kein privater Schlüssel im Zertifikat gefunden"
        }

        return java.io.ByteArrayOutputStream().use { output ->
            target.store(output, newPassword.toCharArray())
            output.toByteArray()
        }
    }

    fun inspectPkcs12(pkcs12: ByteArray, password: String): IdentityInfo {
        require(pkcs12.isNotEmpty()) { "Zertifikatsdatei ist leer" }

        val store = KeyStore.getInstance("PKCS12")
        store.load(pkcs12.inputStream(), password.toCharArray())

        val aliases = store.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            if (!store.isKeyEntry(alias)) continue

            val key = store.getKey(alias, password.toCharArray())
            if (key !is java.security.PrivateKey) continue

            val certificate = store.getCertificate(alias)
                as? java.security.cert.X509Certificate ?: continue

            certificate.checkValidity()

            val fingerprint = java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(certificate.encoded)
                .joinToString(":") {
                    "%02X".format(it.toInt() and 0xFF)
                }

            return IdentityInfo(
                subject = certificate.subjectX500Principal.name,
                issuer = certificate.issuerX500Principal.name,
                validFrom = certificate.notBefore.time,
                validUntil = certificate.notAfter.time,
                fingerprint = fingerprint
            )
        }

        error("Kein Benutzerzertifikat mit privatem Schlüssel gefunden")
    }

    fun saveIdentity(id: String, pkcs12: ByteArray) {
        require(Regex("[a-zA-Z0-9_-]{1,64}").matches(id)) {
            "Ungültige Zertifikatskennung"
        }
        require(pkcs12.isNotEmpty()) { "Zertifikatsdaten fehlen" }

        val destination = java.io.File(identityDirectory, "$id.enc")
        require(!destination.exists()) { "Diese Zertifikatskennung existiert bereits" }

        val temporary = java.io.File.createTempFile("identity-", ".tmp", identityDirectory)
        try {
            temporary.outputStream().use { it.write(encrypt(pkcs12)) }
            check(temporary.renameTo(destination)) {
                "Zertifikat konnte nicht gespeichert werden"
            }
        } finally {
            temporary.delete()
        }
    }

    fun loadIdentity(id: String): ByteArray {
        require(Regex("[a-zA-Z0-9_-]{1,64}").matches(id)) {
            "Ungültige Zertifikatskennung"
        }
        val file = java.io.File(identityDirectory, "$id.enc")
        require(file.isFile) { "Zertifikat nicht gefunden" }
        return decrypt(file.readBytes())
    }

    fun saveIdentityPassword(id: String, password: String) {
        require(Regex("[a-zA-Z0-9_-]{1,64}").matches(id)) {
            "Ungültige Zertifikatskennung"
        }
        val certificate = java.io.File(identityDirectory, "$id.enc")
        require(certificate.isFile) { "Zertifikat muss zuerst gespeichert werden" }
        val destination = java.io.File(identityDirectory, "$id.password.enc")
        require(!destination.exists()) { "Passwort ist bereits gespeichert" }
        val temporary = java.io.File.createTempFile("password-", ".tmp", identityDirectory)
        try {
            temporary.outputStream().use {
                it.write(encrypt(password.toByteArray(Charsets.UTF_8)))
            }
            check(temporary.renameTo(destination)) {
                "Zertifikatspasswort konnte nicht gespeichert werden"
            }
        } finally {
            temporary.delete()
        }
    }

    fun loadIdentityPassword(id: String): String {
        require(Regex("[a-zA-Z0-9_-]{1,64}").matches(id)) {
            "Ungültige Zertifikatskennung"
        }
        val file = java.io.File(identityDirectory, "$id.password.enc")
        require(file.isFile) { "Zertifikatspasswort nicht gefunden" }
        return String(decrypt(file.readBytes()), Charsets.UTF_8)
    }

    fun saveCompleteIdentity(id: String, pkcs12: ByteArray, password: String) {
        require(Regex("[a-zA-Z0-9_-]{1,64}").matches(id)) {
            "Ungültige Zertifikatskennung"
        }
        inspectPkcs12(pkcs12, password)

        val certificateFile = java.io.File(identityDirectory, "$id.enc")
        val passwordFile = java.io.File(identityDirectory, "$id.password.enc")

        require(!certificateFile.exists() && !passwordFile.exists()) {
            "Diese Zertifikatskennung existiert bereits"
        }

        try {
            saveIdentity(id, pkcs12)
            saveIdentityPassword(id, password)
        } catch (error: Exception) {
            certificateFile.delete()
            passwordFile.delete()
            throw error
        }
    }

    fun deleteIdentity(id: String) {
        require(Regex("[a-zA-Z0-9_-]{1,64}").matches(id)) {
            "Ungültige Zertifikatskennung"
        }

        val certificate = java.io.File(identityDirectory, "$id.enc")
        val password = java.io.File(identityDirectory, "$id.password.enc")

        require(certificate.isFile) {
            "Zertifikat nicht gefunden"
        }

        val preferences = context.getSharedPreferences(
            "mumble_identity", Context.MODE_PRIVATE
        )

        check(certificate.delete()) {
            "Zertifikat konnte nicht gelöscht werden"
        }

        if (password.exists()) {
            check(password.delete()) {
                "Zertifikatspasswort konnte nicht gelöscht werden"
            }
        }

        if (preferences.getString("selected_id", null) == id) {
            check(preferences.edit().remove("selected_id").commit()) {
                "Aktive Zertifikatsauswahl konnte nicht zurückgesetzt werden"
            }
        }
    }

    fun selectIdentity(id: String) {
        require(id in listIdentities()) {
            "Dieses Mumble-Zertifikat ist nicht vorhanden"
        }
        val passwordFile = java.io.File(identityDirectory, "$id.password.enc")
        require(passwordFile.isFile) {
            "Das Zertifikat besitzt kein gespeichertes Passwort"
        }
        context.getSharedPreferences("mumble_identity", Context.MODE_PRIVATE)
            .edit().putString("selected_id", id).apply()
    }

    fun selectedIdentityId(): String? {
        val id = context.getSharedPreferences(
            "mumble_identity", Context.MODE_PRIVATE
        ).getString("selected_id", null) ?: return null

        return id.takeIf { it in listIdentities() }
    }

    fun listIdentities(): List<String> =
        identityDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".enc") }
            .map { it.name.removeSuffix(".enc") }
            .filter { Regex("[a-zA-Z0-9_-]{1,64}").matches(it) }
            .sorted()

    fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(data)
        return byteArrayOf(iv.size.toByte()) + iv + encrypted
    }

    fun decrypt(data: ByteArray): ByteArray {
        require(data.isNotEmpty()) { "Verschlüsselte Daten fehlen" }
        val ivLength = data[0].toInt() and 0xFF
        require(ivLength == 12 && data.size > 1 + ivLength + 16) {
            "Ungültige verschlüsselte Zertifikatsdaten"
        }
        val iv = data.copyOfRange(1, 1 + ivLength)
        val encrypted = data.copyOfRange(1 + ivLength, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }
}
