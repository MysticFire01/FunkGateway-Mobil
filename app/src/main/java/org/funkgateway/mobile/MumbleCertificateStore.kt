package org.funkgateway.mobile

import android.content.Context
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate

class MumbleCertificateStore(private val context: Context) {
    private val storeFile = File(context.filesDir, "mumble-certificates.bks")
    private val password = charArrayOf()

    fun getStorePath(): String? =
        storeFile.takeIf { it.isFile }?.absolutePath

    fun getStorePassword(): String = ""

    fun getStoreFormat(): String = "BKS"

    private fun load(): KeyStore {
        val store = KeyStore.getInstance("BKS")
        if (storeFile.isFile) {
            storeFile.inputStream().use { store.load(it, password) }
        } else {
            store.load(null, password)
        }
        return store
    }

    private fun alias(host: String, port: Int): String =
        "${host.trim().lowercase()}:$port"

    fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    data class SavedServerCertificate(
        val host: String,
        val port: Int,
        val certificate: X509Certificate
    )

    fun listServerCertificates(): List<SavedServerCertificate> {
        val store = load()
        return store.aliases().toList().mapNotNull { name ->
            val separator = name.lastIndexOf(':')
            if (separator <= 0) return@mapNotNull null

            val host = name.substring(0, separator)
            val port = name.substring(separator + 1).toIntOrNull()
                ?: return@mapNotNull null
            val certificate = store.getCertificate(name) as? X509Certificate
                ?: return@mapNotNull null

            SavedServerCertificate(host, port, certificate)
        }.sortedWith(compareBy({ it.host }, { it.port }))
    }

    fun getCertificate(host: String, port: Int): X509Certificate? =
        load().getCertificate(alias(host, port)) as? X509Certificate

    fun connectionTrustStore(host: String, port: Int): String? {
        val certificate = getCertificate(host, port) ?: return null
        val store = KeyStore.getInstance("BKS")
        store.load(null, password)
        store.setCertificateEntry("selected-server", certificate)
        val serverKey = MessageDigest.getInstance("SHA-256")
            .digest(alias(host, port).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val file = File(context.cacheDir, "mumble-trust-$serverKey.bks")
        file.outputStream().use { store.store(it, password) }
        return file.absolutePath
    }

    fun deleteServerCertificate(host: String, port: Int): Boolean {
        val store = load()
        val serverAlias = alias(host, port)

        if (!store.containsAlias(serverAlias)) return false

        store.deleteEntry(serverAlias)

        val temporary = File(context.filesDir, "mumble-certificates.tmp")
        temporary.outputStream().use { store.store(it, password) }

        if (!temporary.renameTo(storeFile)) {
            temporary.delete()
            error("TLS-Serverzertifikat konnte nicht gelöscht werden")
        }

        return true
    }

    fun saveCertificate(host: String, port: Int, certificate: X509Certificate) {
        val store = load()
        store.setCertificateEntry(alias(host, port), certificate)
        val temporary = File(context.filesDir, "mumble-certificates.tmp")
        temporary.outputStream().use { store.store(it, password) }
        if (!temporary.renameTo(storeFile)) {
            temporary.delete()
            error("Zertifikatsspeicher konnte nicht gespeichert werden")
        }
    }
}
