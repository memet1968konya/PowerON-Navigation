package com.poweron.navigation.v2.download

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import java.io.File

data class MapDownloadStatus(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val status: Int
) {
    val percent: Int
        get() {
            if (totalBytes <= 0L) return 0

            return (
                downloadedBytes.toDouble() /
                    totalBytes.toDouble() * 100.0
                ).toInt().coerceIn(0, 100)
        }
}

class MapDownloadManager(
    private val context: Context
) {

    companion object {
        /*
         * OpenDrive doğrudan Austria MBTiles bağlantısı hazır olunca
         * yalnız bu adresi değiştireceğiz.
         */
        const val AUSTRIA_MAP_URL =
            "https://download.geofabrik.de/europe/austria-latest.osm.pbf"

        const val AUSTRIA_FILE_NAME =
            "austria-latest.osm.pbf"
    }

    private val downloadManager =
        context.getSystemService(
            Context.DOWNLOAD_SERVICE
        ) as DownloadManager

    fun startAustriaDownload(): Long {
        deleteAustriaMap()

        val request = DownloadManager.Request(
            Uri.parse(AUSTRIA_MAP_URL)
        )
            .setTitle("Austria Haritası")
            .setDescription(
                "PowerON Navigation harita paketi indiriliyor"
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setNotificationVisibility(
                DownloadManager.Request
                    .VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                AUSTRIA_FILE_NAME
            )

        return downloadManager.enqueue(request)
    }

    fun getStatus(downloadId: Long): MapDownloadStatus? {
        val query = DownloadManager.Query()
            .setFilterById(downloadId)

        val cursor: Cursor = downloadManager.query(query)

        cursor.use {
            if (!it.moveToFirst()) {
                return null
            }

            val downloadedIndex = it.getColumnIndex(
                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
            )

            val totalIndex = it.getColumnIndex(
                DownloadManager.COLUMN_TOTAL_SIZE_BYTES
            )

            val statusIndex = it.getColumnIndex(
                DownloadManager.COLUMN_STATUS
            )

            return MapDownloadStatus(
                downloadedBytes = it.getLong(downloadedIndex),
                totalBytes = it.getLong(totalIndex),
                status = it.getInt(statusIndex)
            )
        }
    }

    fun cancel(downloadId: Long) {
        downloadManager.remove(downloadId)
    }

    fun getAustriaMapFile(): File? {
        val directory = context.getExternalFilesDir(
            Environment.DIRECTORY_DOWNLOADS
        ) ?: return null

        return File(directory, AUSTRIA_FILE_NAME)
    }

    fun isAustriaMapDownloaded(): Boolean {
        val file = getAustriaMapFile() ?: return false

        return file.exists() && file.length() > 100_000L
    }

    fun deleteAustriaMap(): Boolean {
        val file = getAustriaMapFile() ?: return false

        return !file.exists() || file.delete()
    }
}
