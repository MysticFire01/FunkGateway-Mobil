package org.funkgateway.mobile

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal
import org.spongycastle.cert.jcajce.JcaX509CertificateConverter
import org.spongycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.spongycastle.operator.jcajce.JcaContentSignerBuilder

object MumbleIdentityGenerator {

    data class GeneratedIdentity(
        val pkcs12: ByteArray,
        val password: String
    )

    fun generate(name: String): GeneratedIdentity {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty() && cleanName.length <= 64) {
            "Bitte einen Namen mit maximal 64 Zeichen eingeben"
        }
        require(cleanName.none { it.isISOControl() }) {
            "Der Name enthält ungültige Zeichen"
        }

        val random = SecureRandom()
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(3072, random)
        val keys = generator.generateKeyPair()

        val now = System.currentTimeMillis()
        val start = Date(now - 60_000L)
        val end = Date(now + 10L * 365L * 24L * 60L * 60L * 1000L)
        val serial = BigInteger(160, random).setBit(159)
        val principal = X500Principal("CN=" + escapeDn(cleanName))

        val builder = JcaX509v3CertificateBuilder(
            principal, serial, start, end, principal, keys.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .build(keys.private)
        val certificate = JcaX509CertificateConverter()
            .getCertificate(builder.build(signer))

        certificate.verify(keys.public)
        certificate.checkValidity()

        val passwordBytes = ByteArray(32)
        random.nextBytes(passwordBytes)
        val password = passwordBytes.joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
        passwordBytes.fill(0)

        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setKeyEntry(
            "FunkGateway", keys.private, password.toCharArray(),
            arrayOf<X509Certificate>(certificate)
        )
        val output = java.io.ByteArrayOutputStream()
        store.store(output, password.toCharArray())

        return GeneratedIdentity(output.toByteArray(), password)
    }

    private fun escapeDn(value: String): String =
        value.replace("\\", "\\\\")
            .replace(",", "\\,")
            .replace("+", "\\+")
            .replace("\"", "\\\"")
            .replace("<", "\\<")
            .replace(">", "\\>")
            .replace(";", "\\;")
            .replace("=", "\\=")
            .let {
                (if (it.startsWith(" ")) "\\" else "") +
                    it +
                    (if (it.endsWith(" ")) "\\" else "")
            }
}
