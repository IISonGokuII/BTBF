package com.btbf.app

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class VideoDownloadHelper(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "btbf_download"
        private const val NOTIFICATION_ID = 9001
        private val VIDEO_EXTENSIONS = listOf(".mp4", ".webm", ".mkv", ".avi", ".mov", ".m4v", ".flv", ".wmv")
        private val STREAM_EXTENSIONS = listOf(".m3u8", ".mpd")
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Download-Fortschritt"
                setShowBadge(false)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    fun isDirectVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return VIDEO_EXTENSIONS.any { lower.contains(it) } ||
               lower.contains("mime=video") ||
               lower.contains("type=video") ||
               (lower.contains("/video/") && lower.contains("format="))
    }

    fun isStreamUrl(url: String): Boolean {
        val lower = url.lowercase()
        return STREAM_EXTENSIONS.any { lower.contains(it) }
    }

    fun isAnyVideoUrl(url: String): Boolean = isDirectVideoUrl(url) || isStreamUrl(url)

    fun downloadDirect(url: String, referer: String?, userAgent: String?, contentDisposition: String? = null, mimeType: String? = null) {
        val fileName = if (contentDisposition != null) {
            URLUtil.guessFileName(url, contentDisposition, mimeType)
        } else {
            "BTBF_${System.currentTimeMillis()}.mp4"
        }
        val cookie = CookieManager.getInstance().getCookie(url)

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("BTBF: $fileName")
            setDescription("Video wird heruntergeladen...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            setMimeType(mimeType ?: "video/mp4")
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            if (!cookie.isNullOrEmpty()) addRequestHeader("Cookie", cookie)
            if (referer != null) addRequestHeader("Referer", referer)
            if (userAgent != null) addRequestHeader("User-Agent", userAgent)
        }

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
    }

    suspend fun downloadHlsStream(
        m3u8Url: String,
        referer: String?,
        userAgent: String?,
        onProgress: (Int, String) -> Unit,
        onComplete: (File?) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            try {
                onProgress(0, "Lade Stream-Info...")
                val ua = userAgent ?: "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36"
                val cookie = CookieManager.getInstance().getCookie(m3u8Url)

                // 1. Master-Playlist laden
                val masterContent = fetchUrl(m3u8Url, referer, ua, cookie)
                if (masterContent == null) {
                    withContext(Dispatchers.Main) { onComplete(null) }
                    return@withContext
                }

                // 2. Beste Qualitaet finden
                val segmentPlaylistUrl = findBestQualityPlaylist(masterContent, m3u8Url)
                val segmentContent = if (segmentPlaylistUrl != m3u8Url) {
                    onProgress(5, "Lade Segment-Liste...")
                    fetchUrl(segmentPlaylistUrl, referer, ua, cookie) ?: masterContent
                } else {
                    masterContent
                }

                // 3. Segmente extrahieren
                val segments = parseSegments(segmentContent, segmentPlaylistUrl)
                if (segments.isEmpty()) {
                    // Vielleicht ist es eine direkte MP4-URL in der Playlist
                    val directUrl = findDirectUrlInPlaylist(masterContent, m3u8Url)
                    if (directUrl != null) {
                        withContext(Dispatchers.Main) {
                            downloadDirect(directUrl, referer, ua)
                            onComplete(null)
                        }
                        return@withContext
                    }
                    withContext(Dispatchers.Main) { onComplete(null) }
                    return@withContext
                }

                onProgress(10, "${segments.size} Segmente gefunden")

                // 4. Segmente herunterladen und zusammenfuegen
                val outputFile = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "BTBF_${System.currentTimeMillis()}.ts"
                )
                val fos = FileOutputStream(outputFile)
                var downloadedCount = 0

                showProgressNotification(0, segments.size)

                for ((index, segUrl) in segments.withIndex()) {
                    val segData = fetchUrlBytes(segUrl, referer, ua, cookie)
                    if (segData != null) {
                        fos.write(segData)
                        downloadedCount++
                    }

                    val progress = 10 + ((index + 1) * 90 / segments.size)
                    val msg = "Segment ${index + 1}/${segments.size}"
                    withContext(Dispatchers.Main) { onProgress(progress, msg) }
                    showProgressNotification(index + 1, segments.size)
                }

                fos.flush()
                fos.close()

                // 5. TS -> MP4 umbenennen (die meisten Player koennen .ts abspielen)
                val mp4File = File(outputFile.parent, outputFile.nameWithoutExtension + ".mp4")
                outputFile.renameTo(mp4File)
                val finalFile = if (mp4File.exists()) mp4File else outputFile

                showCompleteNotification(finalFile.name)

                withContext(Dispatchers.Main) { onComplete(finalFile) }

            } catch (e: Exception) {
                showErrorNotification(e.message ?: "Unbekannter Fehler")
                withContext(Dispatchers.Main) { onComplete(null) }
            }
        }
    }

    private fun findBestQualityPlaylist(content: String, baseUrl: String): String {
        val lines = content.lines()
        var bestBandwidth = 0L
        var bestUrl: String? = null
        var isVariantPlaylist = false

        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                isVariantPlaylist = true
                val bwMatch = Regex("BANDWIDTH=(\\d+)").find(line)
                val bw = bwMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0
                if (bw >= bestBandwidth && i + 1 < lines.size) {
                    bestBandwidth = bw
                    bestUrl = lines[i + 1].trim()
                }
            }
        }

        if (!isVariantPlaylist) return baseUrl

        val url = bestUrl ?: return baseUrl
        return resolveUrl(url, baseUrl)
    }

    private fun parseSegments(content: String, baseUrl: String): List<String> {
        val segments = mutableListOf<String>()
        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                segments.add(resolveUrl(trimmed, baseUrl))
            }
        }
        return segments
    }

    private fun findDirectUrlInPlaylist(content: String, baseUrl: String): String? {
        for (line in content.lines()) {
            val trimmed = line.trim().lowercase()
            if (VIDEO_EXTENSIONS.any { trimmed.contains(it) }) {
                return resolveUrl(line.trim(), baseUrl)
            }
        }
        return null
    }

    private fun resolveUrl(url: String, baseUrl: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("/")) {
            val base = URL(baseUrl)
            return "${base.protocol}://${base.host}$url"
        }
        val lastSlash = baseUrl.lastIndexOf('/')
        return if (lastSlash >= 0) baseUrl.substring(0, lastSlash + 1) + url else url
    }

    private fun fetchUrl(url: String, referer: String?, userAgent: String, cookie: String?): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                if (referer != null) setRequestProperty("Referer", referer)
                if (!cookie.isNullOrEmpty()) setRequestProperty("Cookie", cookie)
                connectTimeout = 15000
                readTimeout = 30000
            }
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val result = reader.readText()
            reader.close()
            conn.disconnect()
            result
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchUrlBytes(url: String, referer: String?, userAgent: String, cookie: String?): ByteArray? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                if (referer != null) setRequestProperty("Referer", referer)
                if (!cookie.isNullOrEmpty()) setRequestProperty("Cookie", cookie)
                connectTimeout = 15000
                readTimeout = 60000
            }
            val data = conn.inputStream.readBytes()
            conn.disconnect()
            data
        } catch (_: Exception) {
            null
        }
    }

    // ==================== NOTIFICATIONS ====================

    private fun showProgressNotification(current: Int, total: Int) {
        try {
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("BTBF Video Download")
                .setContentText("Segment $current/$total")
                .setProgress(total, current, false)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) { }
    }

    private fun showCompleteNotification(fileName: String) {
        try {
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Download abgeschlossen")
                .setContentText(fileName)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) { }
    }

    private fun showErrorNotification(error: String) {
        try {
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Download fehlgeschlagen")
                .setContentText(error)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) { }
    }
}
