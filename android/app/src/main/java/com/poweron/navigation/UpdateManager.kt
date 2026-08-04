package com.poweron.navigation

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(
    private val context: Context
) {

    companion object {
        private const val REPOSITORY =
            "memet1968konya/PowerON-Navigation"

        private const val API_URL =
            "https://api.github.com/repos/$REPOSITORY/releases/latest"
    }

    private var downloadId: Long = -1L
    private var downloadedFile: File? = null

    fun checkForUpdate(showNoUpdateMessage: Boolean = false) {
        Thread {
            try {
                val release = fetchLatestRelease()
                val latestVersion = normalizeVersion(release.tagName)
                val currentVersion = normalizeVersion(
                    context.packageManager
                        .getPackageInfo(context.packageName, 0)
                        .versionName ?: "0.0.0"
                )

                (context as MainActivity).runOnUiThread {
                    if (isNewerVersion(latestVersion, currentVersion)) {
                        showUpdateDialog(release)
                    } else if (showNoUpdateMessage) {
                        Toast.makeText(
                            context,
                            "Uygulama güncel: $currentVersion",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                if (showNoUpdateMessage) {
                    (context as MainActivity).runOnUiThread {
                        Toast.makeText(
                            context,
                            "Güncelleme kontrol edilemedi.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }.start()
    }

    private fun fetchLatestRelease(): ReleaseInfo {
        val connection = URL(API_URL).openConnection()
                as HttpURLConnection

        connection.requestMethod = "GET"
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
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

            var apkUrl: String? = null
            var apkName: String? = null

            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                val name = asset.getString("name")

                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkName = name
                    apkUrl = asset.getString(
                        "browser_download_url"
                    )
                    break
                }
            }

            if (apkUrl == null || apkName == null) {
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

    private fun showUpdateDialog(release: ReleaseInfo) {
        AlertDialog.Builder(context)
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

    private fun downloadApk(release: ReleaseInfo) {
        val directory = context.getExternalFilesDir(
            Environment.DIRECTORY_DOWNLOADS
        ) ?: run {
            Toast.makeText(
                context,
                "İndirme klasörü açılamadı.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val file = File(directory, release.apkName)

        if (file.exists()) {
            file.delete()
        }

        downloadedFile = file

        val request = DownloadManager.Request(
            Uri.parse(release.apkUrl)
        )
            .setTitle("PowerON Navigation")
            .setDescription("Güncelleme indiriliyor")
            .setNotificationVisibility(
                DownloadManager.Request
                    .VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setDestinationUri(Uri.fromFile(file))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val manager = context.getSystemService(
            Context.DOWNLOAD_SERVICE
        ) as DownloadManager

        downloadId = manager.enqueue(request)
        registerDownloadReceiver()

        Toast.makeText(
            context,
            "Güncelleme indiriliyor…",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun registerDownloadReceiver() {
        val filter = IntentFilter(
            DownloadManager.ACTION_DOWNLOAD_COMPLETE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                downloadReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(
                downloadReceiver,
                filter
            )
        }
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(
            receiverContext: Context,
            intent: Intent
        ) {
            val completedId = intent.getLongExtra(
                DownloadManager.EXTRA_DOWNLOAD_ID,
                -1L
            )

            if (completedId != downloadId) {
                return
            }

            try {
                context.unregisterReceiver(this)
            } catch (_: Exception) {
            }

            val file = downloadedFile ?: return

            if (!file.exists() || file.length() == 0L) {
                Toast.makeText(
                    context,
                    "Güncelleme indirilemedi.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            installApk(file)
        }
    }

    private fun installApk(file: File) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            AlertDialog.Builder(context)
                .setTitle("Kurulum izni gerekli")
                .setMessage(
                    "PowerON Navigation güncellemesini " +
                        "kurmak için bu kaynaktan uygulama " +
                        "yükleme iznini aç."
                )
                .setPositiveButton("Ayarları aç") { _, _ ->
                    val settingsIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(settingsIntent)
                }
                .setNegativeButton("İptal", null)
                .show()

            return
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val installIntent = Intent(
            Intent.ACTION_VIEW
        ).apply {
            setDataAndType(
                apkUri,
                "application/vnd.android.package-archive"
            )
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(installIntent)
    }

    private fun normalizeVersion(version: String): String {
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
