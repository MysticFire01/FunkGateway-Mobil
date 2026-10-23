package org.funkgateway.mobile

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

object MobileUpdateVerifier {

    @Suppress("DEPRECATION")
    fun verify(
        context: Context,
        apk: File,
        update: MobileUpdateInfo
    ): Result<Unit> = runCatching {
        require(apk.isFile && apk.length() > 0) {
            "APK fehlt oder ist leer"
        }

        val pm = context.packageManager

        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

        val installed = pm.getPackageInfo(
            context.packageName, flags
        )

        val candidate = pm.getPackageArchiveInfo(
            apk.absolutePath, flags
        ) ?: error("APK kann nicht gelesen werden")

        require(candidate.packageName == context.packageName) {
            "Falsche Paketkennung"
        }

        fun versionCode(
            info: android.content.pm.PackageInfo
        ): Long {
            return if (Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
        }

        require(versionCode(candidate) > versionCode(installed)) {
            "APK ist nicht neuer als die installierte Version"
        }

        require(versionCode(candidate) == update.versionCode.toLong()) {
            "APK-Version stimmt nicht mit dem GitHub-Release überein"
        }

        fun signatures(
            info: android.content.pm.PackageInfo
        ): Set<String> {
            val certs = if (Build.VERSION.SDK_INT >= 28) {
                info.signingInfo?.apkContentsSigners
            } else {
                info.signatures
            } ?: emptyArray()

            return certs.map { signature ->
                MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .joinToString("") { byte ->
                        "%02x".format(byte.toInt() and 255)
                    }
            }.toSet()
        }

        val expected = signatures(installed)
        val actual = signatures(candidate)

        require(expected.isNotEmpty() && actual.isNotEmpty()) {
            "APK-Signatur konnte nicht ermittelt werden"
        }

        require(actual == expected) {
            "APK-Signatur stimmt nicht mit der installierten App überein"
        }
    }
}
