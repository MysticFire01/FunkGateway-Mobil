package org.funkgateway.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MobileUpdateInfo(
    val version: String,
    val versionCode: Int,
    val changelog: String,
    val apkUrl: String
)

object MobileUpdater {
    private const val RELEASE_API =
        "https://api.github.com/repos/MysticFire01/FunkGateway-Mobil/releases/latest"

    private const val RELEASES_API =
        "https://api.github.com/repos/MysticFire01/FunkGateway-Mobil/releases?per_page=30"

    fun check(
        context: Context,
        testChannel: Boolean = false,
        callback: (Result<MobileUpdateInfo?>) -> Unit
    ) {
        val appContext = context.applicationContext
        Thread {
            val result = runCatching {
                val apiUrl = if (testChannel) RELEASES_API else RELEASE_API
                val connection = URL(apiUrl)
                    .openConnection() as HttpURLConnection

                try {
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    connection.setRequestProperty(
                        "Accept", "application/vnd.github+json"
                    )
                    connection.setRequestProperty(
                        "User-Agent", "FunkGateway-Mobile"
                    )

                    if (connection.responseCode != 200) {
                        error("GitHub HTTP ${connection.responseCode}")
                    }

                    val response = connection.inputStream
                        .bufferedReader().use { it.readText() }

                    val json = if (testChannel) {
                        val releases = org.json.JSONArray(response)
                        val candidates = (0 until releases.length())
                            .map { releases.getJSONObject(it) }
                            .filter {
                                it.optBoolean("prerelease") &&
                                !it.optBoolean("draft") &&
                                Regex("v[0-9]+[.][0-9]+[.][0-9]+")
                                    .matches(it.optString("tag_name"))
                            }
                        candidates.maxByOrNull {
                            val parts = it.getString("tag_name")
                                .removePrefix("v")
                                .split(".")
                                .map(String::toLong)
                            parts[0] * 1000000L +
                                parts[1] * 1000L + parts[2]
                        } ?: return@runCatching null
                    } else {
                        JSONObject(response)
                    }

                    val tag = json.getString("tag_name")
                    val code = tag.removePrefix("v")
                        .split(".")
                        .let { parts ->
                            require(parts.size == 3) {
                                "Versionsformat muss vX.Y.Z sein"
                            }
                            val numbers = parts.map { it.toInt() }
                            require(numbers.all { it in 0..999 }) {
                                "Ungültige Versionsnummer"
                            }
                            numbers[0] * 1000000 +
                                numbers[1] * 1000 + numbers[2]
                        }

                    val assets = json.getJSONArray("assets")
                    var apkUrl = ""

                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.getString("name") ==
                            "FunkGateway-Mobile.apk") {
                            apkUrl = asset.getString(
                                "browser_download_url"
                            )
                            break
                        }
                    }

                    require(apkUrl.isNotEmpty()) {
                        "Keine FunkGateway-Mobile.apk im Release"
                    }

                    require(
                        apkUrl.startsWith(
                            "https://github.com/MysticFire01/FunkGateway-Mobil/releases/download/"
                        )
                    ) {
                        "Unerwartete APK-Adresse"
                    }

                    val installedCode = if (
                        android.os.Build.VERSION.SDK_INT >= 28
                    ) {
                        appContext.packageManager.getPackageInfo(
                            appContext.packageName, 0
                        ).longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        val info = appContext.packageManager.getPackageInfo(
                            appContext.packageName, 0
                        )
                        @Suppress("DEPRECATION")
                        info.versionCode.toLong()
                    }

                    if (code.toLong() <= installedCode) {
                        null
                    } else {
                        MobileUpdateInfo(
                            version = tag,
                            versionCode = code,
                            changelog = json.optString("body"),
                            apkUrl = apkUrl
                        )
                    }
                } finally {
                    connection.disconnect()
                }
            }

            Handler(Looper.getMainLooper()).post {
                callback(result)
            }
        }.start()
    }
}
