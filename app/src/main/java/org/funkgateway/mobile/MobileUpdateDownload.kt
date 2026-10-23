package org.funkgateway.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object MobileUpdateDownload {
    private const val MAX_BYTES = 100L * 1024L * 1024L
    private const val RELEASE_PREFIX =
        "https://github.com/MysticFire01/FunkGateway-Mobil/releases/download/"
    private val allowedHosts = setOf(
        "github.com",
        "release-assets.githubusercontent.com",
        "objects.githubusercontent.com"
    )

    fun download(
        context: Context,
        update: MobileUpdateInfo,
        onProgress: (Int) -> Unit,
        onFinished: (Result<File>) -> Unit
    ) {
        val appContext = context.applicationContext
        val handler = Handler(Looper.getMainLooper())

        Thread {
            var temporary: File? = null

            val result = runCatching {
                require(update.apkUrl.startsWith(RELEASE_PREFIX)) {
                    "Unzulässige Update-Adresse"
                }

                val directory = File(appContext.cacheDir, "updates")
                require(directory.isDirectory || directory.mkdirs()) {
                    "Update-Ordner konnte nicht erstellt werden"
                }

                val target = File(directory, "FunkGateway-Mobile.apk")
                val part = File(directory, "FunkGateway-Mobile.apk.part")
                temporary = part

                if (part.exists()) part.delete()

                var current = URL(update.apkUrl)
                var connection: HttpURLConnection? = null

                try {
                    for (redirect in 0..5) {
                        require(
                            current.protocol == "https" &&
                                current.host.lowercase() in allowedHosts &&
                                current.userInfo == null &&
                                current.port == -1
                        ) {
                            "Unzulässiger Downloadserver"
                        }

                        connection?.disconnect()
                        connection = current.openConnection() as HttpURLConnection
                        connection.instanceFollowRedirects = false
                        connection.connectTimeout = 15000
                        connection.readTimeout = 30000
                        connection.setRequestProperty(
                            "User-Agent", "FunkGateway-Mobile"
                        )

                        val status = connection.responseCode
                        if (status in 300..399) {
                            require(redirect < 5) {
                                "Zu viele Weiterleitungen"
                            }
                            val location = connection.getHeaderField("Location")
                                ?: error("Weiterleitung ohne Ziel")
                            current = URL(current, location)
                        } else {
                            require(status == 200) {
                                "Download HTTP $status"
                            }
                            break
                        }
                    }

                    val active = connection ?: error("Keine Verbindung")
                    val length = active.contentLengthLong

                    require(length <= MAX_BYTES) {
                        "APK ist zu groß"
                    }

                    var received = 0L
                    var lastPercent = -1

                    active.inputStream.use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(32768)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                received += count

                                require(received <= MAX_BYTES) {
                                    "Download überschreitet 100 MB"
                                }

                                output.write(buffer, 0, count)

                                if (length > 0) {
                                    val percent =
                                        (received * 100 / length).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        handler.post { onProgress(percent) }
                                    }
                                }
                            }
                        }
                    }

                    require(received > 0) {
                        "Leere APK heruntergeladen"
                    }
                    require(length < 0 || received == length) {
                        "Download unvollständig"
                    }

                    if (target.exists()) {
                        require(target.delete()) {
                            "Alte Download-Datei konnte nicht entfernt werden"
                        }
                    }
                    require(part.renameTo(target)) {
                        "Download-Datei konnte nicht gespeichert werden"
                    }

                    target
                } finally {
                    connection?.disconnect()
                }
            }

            if (result.isFailure) temporary?.delete()

            handler.post { onFinished(result) }
        }.start()
    }
}
