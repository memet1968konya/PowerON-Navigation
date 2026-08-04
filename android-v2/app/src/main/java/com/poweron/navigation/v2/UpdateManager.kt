package com.poweron.navigation.v2

import android.app.AlertDialog
import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(
    private val activity: MainActivity
) {

    companion object {
        private const val REPOSITORY =
            "memet1968konya/PowerON-Navigation"

        private const val API_URL =
            "https://api.github.com/repos/$REPOSITORY/releases/latest"

        private const val PREFS = "poweron_update"
        private const val PENDING_FILE = "pending_apk"
    }

    fun checkForUpdate(showNoUpdateMessage: Boolean = false) {
        Thread {
            try {
                val release = fetchLatestRelease()

                val latestVersion =
                    normalizeVersion(release.tagName)

                val currentVersion =
                    normalizeVersion(
                        activity.packageManager
                            .getPackageInfo(
                                activity.packageName,
                                0
                            )
                            .versionName ?: "0.0.0"
                    )

                activity.runOnUiThread {
                    if (
                        isNewerVersion(
                            latestVersion,
                            currentVersion
                        )
                    ) {
                        showUpdateDialog(release)
                    } else if (showNoUpdateMessage) {
                        Toast.makeText(
                            activity,
                            "Uygulama güncel: $currentVersion",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    if (showNoUpdateMessage) {
                        Toast.makeText(
                            activity,
                            "Güncelleme kontrol edilemedi: " +
                                (e.message ?: "Bilinmeyen hata"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }.start()
    }

    fun resumePendingInstall() {
        val prefs = activity.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

        val filePath = prefs.getString(
            PENDING_FILE,
            null
        ) ?: return

        val file = File(filePath)

        if (!file.exists() || file.length() == 0L) {
            prefs.edit().remove(PENDING_FILE).apply()
            return
        }

        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            activity.packageManager.canRequestPackageInstalls()
        ) {
            openInstaller(file)
        }
    }

    private fun fetchLatestRelease(): ReleaseInfo {
        val connection =
            URL(API_URL).openConnection()
                as HttpURLConnection

        connection.requestMethod = "GET"
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.instanceFollowRedirects = true

        connection.setRequestProperty(
            "Accept",
            "application/vnd.github+json"
        )

        connection.setRequestProperty(
            "User-Agent",
            "PowerON-Navigation"
        )

        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException(
                    "GitHub HTTP ${connection.responseCode}"
                )
            }

            val json = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            val root = JSONObject(json)
            val assets = root.getJSONArray("assets")

            var apkName: String? = null
            var apkUrl: String? = null

            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                val name = asset.getString("name")

                if (name.endsWith(".apk", true)) {
                    apkName = name
                    apkUrl = asset.getString(
                        "browser_download_url"
                    )
                    break
                }
            }

            if (apkName == null || apkUrl == null) {
                throw IllegalStateException(
                    "Release içinde APK bulunamadı."
                )
            }

            return ReleaseInfo(
                tagName = root.getString("tag_name"),
                name = root.optString(
                    "name",
                    root.getString("tag_name")
                ),
                notes = root.optString(
                    "body",
                    "Yeni sürüm hazır."
                ),
                apkName = apkName,
                apkUrl = apkUrl
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun showUpdateDialog(
        release: ReleaseInfo
    ) {
        AlertDialog.Builder(activity)
            .setTitle("Yeni güncelleme")
            .setMessage(
                "${release.name}\n\n${release.notes}"
            )
            .setPositiveButton("Güncelle") { _, _ ->
                downloadApk(release)
            }
            .setNegativeButton("Daha sonra", null)
            .show()
    }

    private fun downloadApk(
        release: ReleaseInfo
    ) {
        Toast.makeText(
            activity,
            "Güncelleme indiriliyor…",
            Toast.LENGTH_LONG
        ).show()

        Thread {
            try {
                val updateDir = File(
                    activity.cacheDir,
                    "updates"
                )

                updateDir.mkdirs()

                val apkFile = File(
                    updateDir,
                    release.apkName
                )

                if (apkFile.exists()) {
                    apkFile.delete()
                }

                downloadFile(
                    release.apkUrl,
                    apkFile
                )

                if (
                    !apkFile.exists() ||
                    apkFile.length() < 100_000L
                ) {
                    throw IllegalStateException(
                        "İndirilen APK geçersiz."
                    )
                }

                activity.getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
                )
                    .edit()
                    .putString(
                        PENDING_FILE,
                        apkFile.absolutePath
                    )
                    .apply()

                activity.runOnUiThread {
                    requestInstallOrOpen(apkFile)
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        "Güncelleme indirilemedi: " +
                            (e.message ?: "Bilinmeyen hata"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun downloadFile(
        url: String,
        output: File
    ) {
        var currentUrl = url
        var redirectCount = 0

        while (redirectCount < 8) {
            val connection =
                URL(currentUrl).openConnection()
                    as HttpURLConnection

            connection.requestMethod = "GET"
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            connection.instanceFollowRedirects = false

            connection.setRequestProperty(
                "User-Agent",
                "PowerON-Navigation"
            )

            val responseCode = connection.responseCode

            if (
                responseCode in 300..399
            ) {
                val nextUrl =
                    connection.getHeaderField("Location")
                        ?: throw IllegalStateException(
                            "Yönlendirme adresi alınamadı."
                        )

                connection.disconnect()
                currentUrl = nextUrl
                redirectCount++
                continue
            }

            if (responseCode !in 200..299) {
                connection.disconnect()

                throw IllegalStateException(
                    "APK HTTP $responseCode"
                )
            }

            connection.inputStream.use { input ->
                output.outputStream().use { outputStream ->
                    input.copyTo(
                        outputStream,
                        bufferSize = 1024 * 128
                    )
                }
            }

            connection.disconnect()
            return
        }

        throw IllegalStateException(
            "Çok fazla yönlendirme."
        )
    }

    private fun requestInstallOrOpen(
        file: File
    ) {
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O &&
            !activity.packageManager
                .canRequestPackageInstalls()
        ) {
            AlertDialog.Builder(activity)
                .setTitle("Kurulum izni gerekli")
                .setMessage(
                    "Güncellemeyi kurmak için " +
                        "\"Bu kaynaktan izin ver\" " +
                        "ayarını aç."
                )
                .setPositiveButton("Ayarları aç") { _, _ ->
                    val intent = Intent(
                        Settings
                            .ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse(
                            "package:${activity.packageName}"
                        )
                    )

                    activity.startActivity(intent)
                }
                .setNegativeButton("İptal", null)
                .show()

            return
        }

        openInstaller(file)
    }

    private fun openInstaller(file: File) {
        if (!file.exists() || file.length() < 100_000L) {
            Toast.makeText(
                activity,
                "İndirilen APK bulunamadı veya geçersiz.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val apkUri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            file
        )

        activity.grantUriPermission(
            "com.google.android.packageinstaller",
            apkUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        activity.grantUriPermission(
            "com.android.packageinstaller",
            apkUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        val installIntent = Intent(
            Intent.ACTION_INSTALL_PACKAGE
        ).apply {
            data = apkUri
            clipData = ClipData.newRawUri(
                "PowerON Navigation Güncelleme",
                apkUri
            )

            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            putExtra(
                Intent.EXTRA_NOT_UNKNOWN_SOURCE,
                true
            )
        }

        activity.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        ).edit().remove(PENDING_FILE).apply()

        try {
            activity.startActivity(installIntent)
        } catch (firstError: Exception) {
            val fallbackIntent = Intent(
                Intent.ACTION_VIEW
            ).apply {
                setDataAndType(
                    apkUri,
                    "application/vnd.android.package-archive"
                )

                clipData = ClipData.newRawUri(
                    "PowerON Navigation Güncelleme",
                    apkUri
                )

                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                activity.startActivity(fallbackIntent)
            } catch (secondError: Exception) {
                Toast.makeText(
                    activity,
                    "Kurulum ekranı açılamadı: " +
                        (secondError.message ?: "Bilinmeyen hata"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun normalizeVersion(
        version: String
    ): String {
        return version
            .trim()
            .removePrefix("v")
            .substringBefore("-")
    }

    private fun isNewerVersion(
        latest: String,
        current: String
    ): Boolean {
        val latestParts = latest.split(".")
            .map { it.toIntOrNull() ?: 0 }

        val currentParts = current.split(".")
            .map { it.toIntOrNull() ?: 0 }

        val count = maxOf(
            latestParts.size,
            currentParts.size
        )

        for (index in 0 until count) {
            val latestPart =
                latestParts.getOrElse(index) { 0 }

            val currentPart =
                currentParts.getOrElse(index) { 0 }

            if (latestPart > currentPart) return true
            if (latestPart < currentPart) return false
        }

        return false
    }

    private data class ReleaseInfo(
        val tagName: String,
        val name: String,
        val notes: String,
        val apkName: String,
        val apkUrl: String
    )
}
