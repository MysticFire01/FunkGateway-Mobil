package org.funkgateway.mobile

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

object MobileUpdateInstaller {

    fun install(
        activity: Activity,
        apk: File,
        update: MobileUpdateInfo
    ): Result<Unit> = runCatching {

        MobileUpdateVerifier.verify(
            activity, apk, update
        ).getOrThrow()

        if (
            Build.VERSION.SDK_INT >= 26 &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")
            )

            activity.startActivity(settingsIntent)

            // Nach der Freigabe muss der Nutzer die
            // Installation erneut ausdrücklich starten.
            return@runCatching
        }

        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apk
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                uri,
                "application/vnd.android.package-archive"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        activity.startActivity(intent)
    }
}
