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
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class VideoDownloadHelper(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "btbf_download"
        private const val NOTIFICATION_ID = 9001
        private const val FETCH_MAX_ATTEMPTS = 3
        private const val FETCH_RETRY_DELAY_MS = 400L
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

    /** MPEG-DASH (.mpd) – nicht als eine Datei per DownloadManager/HLS-Logik speicherbar. */
    fun isDashUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".mpd") || lower.contains("type=application/dash+xml")
    }

    fun downloadDirect(url: String, referer: String?, userAgent: String?, contentDisposition: String? = null, mimeType: String? = null): Long {
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
        return dm.enqueue(request)
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

                // 3. Segmente extrahieren (inkl. AES-128 / EXT-X-KEY)
                val parseResult = parseHlsMediaPlaylist(segmentContent, segmentPlaylistUrl, referer, ua, cookie)
                val segmentEntries = when (parseResult) {
                    is HlsParseResult.Unsupported -> {
                        showErrorNotification(parseResult.reason)
                        withContext(Dispatchers.Main) { onComplete(null) }
                        return@withContext
                    }
                    is HlsParseResult.Ok -> parseResult.segments
                }

                if (segmentEntries.isEmpty()) {
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

                onProgress(10, "${segmentEntries.size} Segmente gefunden")

                // 4. Segmente herunterladen, ggf. AES-128-CBC entschluesseln, zusammenfuegen
                val outputFile = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "BTBF_${System.currentTimeMillis()}.ts"
                )
                val fos = FileOutputStream(outputFile)
                var downloadedCount = 0

                showProgressNotification(0, segmentEntries.size)

                for ((index, entry) in segmentEntries.withIndex()) {
                    val raw = fetchUrlBytes(entry.url, referer, ua, cookie)
                    val segData = when {
                        raw == null -> null
                        entry.decryptKey != null && entry.decryptIv != null -> {
                            val plain = decryptAes128Cbc(raw, entry.decryptKey, entry.decryptIv)
                            if (plain == null) {
                                showErrorNotification("AES-Entschlüsselung Segment ${index + 1} fehlgeschlagen")
                                fos.close()
                                outputFile.delete()
                                withContext(Dispatchers.Main) { onComplete(null) }
                                return@withContext
                            }
                            plain
                        }
                        else -> raw
                    }
                    if (segData != null) {
                        fos.write(segData)
                        downloadedCount++
                    }

                    val progress = 10 + ((index + 1) * 90 / segmentEntries.size)
                    val msg = "Segment ${index + 1}/${segmentEntries.size}"
                    withContext(Dispatchers.Main) { onProgress(progress, msg) }
                    showProgressNotification(index + 1, segmentEntries.size)
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

    private sealed class HlsParseResult {
        data class Ok(val segments: List<HlsSegmentEntry>) : HlsParseResult()
        data class Unsupported(val reason: String) : HlsParseResult()
    }

    private data class HlsSegmentEntry(
        val url: String,
        val decryptKey: ByteArray?,
        val decryptIv: ByteArray?
    )

    /**
     * Parst eine Media-Playlist inkl. [#EXT-X-KEY](METHOD=AES-128) und IV/Media-Sequence.
     */
    private fun parseHlsMediaPlaylist(
        content: String,
        baseUrl: String,
        referer: String?,
        userAgent: String,
        cookie: String?
    ): HlsParseResult {
        var mediaSeq = 0L
        var currentKey: ByteArray? = null
        var currentIvExplicit: ByteArray? = null

        val segments = mutableListOf<HlsSegmentEntry>()

        for (rawLine in content.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                val num = line.substringAfter(":").trim().substringBefore(",").toLongOrNull() ?: 0L
                mediaSeq = num
                continue
            }

            if (line.startsWith("#EXT-X-KEY:")) {
                val method = extractHlsMethod(line)?.uppercase() ?: "NONE"
                when {
                    method == "NONE" || method.isEmpty() -> {
                        currentKey = null
                        currentIvExplicit = null
                    }
                    method == "AES-128" -> {
                        val keyUri = extractKeyUriFromLine(line)
                            ?: return HlsParseResult.Unsupported("EXT-X-KEY ohne URI")
                        val keyUrl = resolveUrl(keyUri, baseUrl)
                        val keyRaw = fetchUrlBytes(keyUrl, referer, userAgent, cookie)
                            ?: return HlsParseResult.Unsupported("AES-Schlüssel nicht ladbar")
                        currentKey = normalizeAesKeyMaterial(keyRaw)
                            ?: return HlsParseResult.Unsupported("Ungültiger AES-128-Schlüssel")
                        currentIvExplicit = extractIvFromKeyLine(line)
                    }
                    method.contains("SAMPLE") -> {
                        return HlsParseResult.Unsupported("SAMPLE-AES wird nicht unterstützt")
                    }
                    else -> {
                        return HlsParseResult.Unsupported("Verschlüsselung $method nicht unterstützt")
                    }
                }
                continue
            }

            if (line.startsWith("#")) continue

            val segUrl = resolveUrl(line, baseUrl)
            val iv = if (currentKey != null) {
                currentIvExplicit ?: mediaSequenceToIv(mediaSeq)
            } else {
                null
            }
            segments.add(HlsSegmentEntry(segUrl, currentKey, iv))
            mediaSeq++
        }

        return HlsParseResult.Ok(segments)
    }

    private fun extractHlsMethod(line: String): String? {
        val m = Regex("""METHOD=([^,\s"]+)""", RegexOption.IGNORE_CASE).find(line)
        return m?.groupValues?.get(1)?.trim()
    }

    private fun extractKeyUriFromLine(line: String): String? {
        Regex("""URI="([^"]+)"""").find(line)?.let { return it.groupValues[1] }
        Regex("""URI='([^']+)'""").find(line)?.let { return it.groupValues[1] }
        val m = Regex("""URI=([^,\s]+)""").find(line) ?: return null
        return m.groupValues[1].trim().trim('"')
    }

    private fun extractIvFromKeyLine(line: String): ByteArray? {
        Regex("""IV=0x([0-9a-fA-F]+)""").find(line)?.let {
            hexToBytesEven(it.groupValues[1])?.let { b -> return b }
        }
        Regex("""IV=([0-9a-fA-F]{32})""").find(line)?.let {
            return hexToBytesEven(it.groupValues[1])
        }
        return null
    }

    private fun hexToBytesEven(hex: String): ByteArray? {
        val h = hex.trim()
        if (h.length % 2 != 0) return null
        return try {
            ByteArray(h.length / 2) { i ->
                h.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun mediaSequenceToIv(seq: Long): ByteArray {
        val b = ByteArray(16)
        var x = seq
        for (i in 15 downTo 0) {
            b[i] = (x and 0xffL).toByte()
            x = x ushr 8
        }
        return b
    }

    private fun normalizeAesKeyMaterial(raw: ByteArray): ByteArray? {
        if (raw.size == 16) return raw
        val asText = try {
            String(raw, Charsets.UTF_8).trim()
        } catch (_: Exception) {
            return null
        }
        if (asText.length >= 32 && asText.take(32).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return hexToBytesEven(asText.take(32))
        }
        return null
    }

    private fun decryptAes128Cbc(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            null
        }
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
        repeat(FETCH_MAX_ATTEMPTS) { attempt ->
            fetchUrlOnce(url, referer, userAgent, cookie)?.let { return it }
            if (attempt < FETCH_MAX_ATTEMPTS - 1) Thread.sleep(FETCH_RETRY_DELAY_MS)
        }
        return null
    }

    private fun fetchUrlOnce(url: String, referer: String?, userAgent: String, cookie: String?): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                if (referer != null) setRequestProperty("Referer", referer)
                if (!cookie.isNullOrEmpty()) setRequestProperty("Cookie", cookie)
                connectTimeout = 20000
                readTimeout = 35000
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
        repeat(FETCH_MAX_ATTEMPTS) { attempt ->
            fetchUrlBytesOnce(url, referer, userAgent, cookie)?.let { return it }
            if (attempt < FETCH_MAX_ATTEMPTS - 1) Thread.sleep(FETCH_RETRY_DELAY_MS)
        }
        return null
    }

    private fun fetchUrlBytesOnce(url: String, referer: String?, userAgent: String, cookie: String?): ByteArray? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                if (referer != null) setRequestProperty("Referer", referer)
                if (!cookie.isNullOrEmpty()) setRequestProperty("Cookie", cookie)
                connectTimeout = 20000
                readTimeout = 90000
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
