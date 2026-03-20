package com.btbf.app.scraper

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.btbf.app.VideoDownloadHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Lädt die Video-Seite in einer versteckten WebView, sammelt per [shouldInterceptRequest]
 * sichtbare Stream-/Video-URLs und ergänzt DOM-/Script-Heuristiken – Fallback wenn Jsoup
 * keine Quellen findet (wichtig für Abspielen und HLS-Download mit Cookies).
 */
class WebViewDetailExtractor(private val activity: Activity) {

    private val handler = Handler(Looper.getMainLooper())
    private val urlDetector = VideoDownloadHelper(activity)

    companion object {
        private const val EXTRACT_JS = """
            (function(){
              try {
                function addUrl(arr, url, typ) {
                  if (!url || url.indexOf('blob:') === 0) return;
                  var i;
                  for (i = 0; i < arr.length; i++) { if (arr[i].url === url) return; }
                  arr.push({url: url, type: typ});
                }
                var sources = [];
                var v = document.querySelector('video');
                if (v) {
                  function typ(u) {
                    if (u.indexOf('.m3u8') >= 0) return 'hls';
                    if (u.indexOf('.mpd') >= 0) return 'dash';
                    return 'direct';
                  }
                  var cs = v.currentSrc || '';
                  if (cs && cs.indexOf('blob:') !== 0) addUrl(sources, cs, typ(cs));
                  else if (v.src && v.src.indexOf('blob:') !== 0) addUrl(sources, v.src, typ(v.src));
                  var subs = v.querySelectorAll('source');
                  for (var j = 0; j < subs.length; j++) {
                    var s = subs[j];
                    var src = s.src || s.getAttribute('src');
                    if (!src || src.indexOf('blob:') === 0) continue;
                    addUrl(sources, src, typ(src));
                  }
                }
                var scr = document.querySelectorAll('script');
                var reH = /https?:\/\/[^\s'"<>]+\.m3u8[^\s'"<>]*/g;
                var reM = /https?:\/\/[^\s'"<>]+\.(mp4|webm)[^\s'"<>]*/gi;
                for (var k = 0; k < scr.length; k++) {
                  var text = scr[k].textContent || '';
                  if (text.length > 600000) text = text.substring(0, 600000);
                  var m;
                  while ((m = reH.exec(text)) !== null) {
                    addUrl(sources, m[0], 'hls');
                  }
                  reH.lastIndex = 0;
                  while ((m = reM.exec(text)) !== null) {
                    addUrl(sources, m[0], 'direct');
                  }
                }
                var ogV = document.querySelector('meta[property="og:video"]');
                if (ogV) {
                  var c = ogV.getAttribute('content');
                  if (c) {
                    var tv = c.indexOf('.m3u8') >= 0 ? 'hls' : 'direct';
                    addUrl(sources, c, tv);
                  }
                }
                var h1 = document.querySelector('h1');
                var title = h1 ? (h1.textContent || '').trim() : '';
                var ogT = document.querySelector('meta[property="og:title"]');
                if (!title && ogT) title = (ogT.getAttribute('content') || '').trim();
                if (!title) title = document.title || '';
                var ogI = document.querySelector('meta[property="og:image"]');
                var thumb = ogI ? (ogI.getAttribute('content') || '') : '';
                if (!thumb && v) thumb = v.getAttribute('poster') || '';
                return JSON.stringify({ title: title, thumbnailUrl: thumb, sources: sources });
              } catch (e) {
                return JSON.stringify({ title: '', thumbnailUrl: '', sources: [] });
              }
            })()
        """
    }

    suspend fun extract(pageUrl: String): VideoDetail? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val lock = Any()
            val capturedDirect = mutableListOf<String>()
            val capturedStream = mutableListOf<String>()

            fun captureUrl(raw: String?) {
                if (raw.isNullOrEmpty()) return
                val u = raw.substringBefore('#')
                synchronized(lock) {
                    if (urlDetector.isStreamUrl(u) && !capturedStream.contains(u)) capturedStream.add(u)
                    if (urlDetector.isDirectVideoUrl(u) && !capturedDirect.contains(u)) capturedDirect.add(u)
                }
            }

            val container = FrameLayout(activity)
            container.layoutParams = FrameLayout.LayoutParams(1, 1)
            val wv = WebView(activity)
            wv.layoutParams = FrameLayout.LayoutParams(1, 1)
            wv.alpha = 0f

            val root = activity.window.decorView as ViewGroup
            root.addView(container)
            container.addView(wv)

            fun removeAndDestroy() {
                try {
                    container.removeView(wv)
                    root.removeView(container)
                    wv.stopLoading()
                    wv.destroy()
                } catch (_: Exception) {
                }
            }

            CookieManager.getInstance().setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            }

            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = WebViewListingExtractor.DEFAULT_USER_AGENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    mediaPlaybackRequiresUserGesture = false
                }
                loadWithOverviewMode = true
                useWideViewPort = true
            }
            wv.webChromeClient = WebChromeClient()

            var completed = false
            var extractGeneration = 0
            var timeoutRunnable: Runnable? = null

            fun buildFromSnapshot(domJson: String?): VideoDetail? {
                val domSources = parseDomSources(domJson)
                val snapStream = synchronized(lock) { capturedStream.toList() }
                val snapDirect = synchronized(lock) { capturedDirect.toList() }
                val merged = mutableListOf<VideoSource>()
                for (u in snapStream) {
                    val t = when {
                        u.contains(".mpd", true) -> VideoSourceType.DASH
                        else -> VideoSourceType.HLS
                    }
                    if (merged.none { it.url == u }) merged.add(VideoSource(u, "", t))
                }
                for (u in snapDirect) {
                    val t = when {
                        u.contains(".mpd", true) -> VideoSourceType.DASH
                        u.contains(".m3u8", true) -> VideoSourceType.HLS
                        else -> VideoSourceType.DIRECT
                    }
                    if (merged.none { it.url == u }) merged.add(VideoSource(u, "", t))
                }
                for (s in domSources) {
                    if (merged.none { it.url == s.url }) merged.add(s)
                }
                if (merged.isEmpty()) return null
                val meta = parseDomMeta(domJson)
                return VideoDetail(
                    title = meta.first,
                    videoSources = sortSources(merged),
                    thumbnailUrl = meta.second
                )
            }

            fun finish(detail: VideoDetail?) {
                if (completed) return
                completed = true
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                removeAndDestroy()
                if (cont.isActive) cont.resume(detail)
            }

            val tr = Runnable {
                if (completed || !cont.isActive) return@Runnable
                finish(buildFromSnapshot(null))
            }
            timeoutRunnable = tr
            handler.postDelayed(tr, 32000)

            val bridge = object {
                @JavascriptInterface
                fun done(domJson: String) {
                    handler.post {
                        if (!cont.isActive || completed) {
                            removeAndDestroy()
                            return@post
                        }
                        val detail = buildFromSnapshot(domJson)
                        finish(detail)
                    }
                }
            }
            wv.addJavascriptInterface(bridge, "BtbfDetailExtract")

            wv.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    synchronized(lock) {
                        capturedDirect.clear()
                        capturedStream.clear()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                    captureUrl(url)
                    return super.shouldInterceptRequest(view, url)
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    captureUrl(request?.url?.toString())
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    val gen = ++extractGeneration
                    handler.postDelayed({
                        if (gen != extractGeneration || completed) return@postDelayed
                        view.evaluateJavascript(
                            "(function(){try{var v=document.querySelector('video');if(v&&v.play)v.play();}catch(e){}})()",
                            null
                        )
                    }, 400)
                    handler.postDelayed({
                        if (gen != extractGeneration || completed) return@postDelayed
                        val script = "BtbfDetailExtract.done($EXTRACT_JS);"
                        view.evaluateJavascript(script, null)
                    }, 5200)
                }
            }

            cont.invokeOnCancellation {
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                handler.post { removeAndDestroy() }
            }

            wv.loadUrl(pageUrl)
        }
    }

    private fun parseDomMeta(domJson: String?): Pair<String, String> {
        if (domJson.isNullOrEmpty()) return "" to ""
        return try {
            val o = JSONObject(domJson)
            o.optString("title", "").trim() to o.optString("thumbnailUrl", "").trim()
        } catch (_: Exception) {
            "" to ""
        }
    }

    private fun parseDomSources(domJson: String?): List<VideoSource> {
        if (domJson.isNullOrEmpty()) return emptyList()
        return try {
            val o = JSONObject(domJson)
            val arr = o.optJSONArray("sources") ?: return emptyList()
            val out = mutableListOf<VideoSource>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val url = item.optString("url", "")
                if (url.isEmpty()) continue
                val t = item.optString("type", "direct")
                val type = when (t) {
                    "hls" -> VideoSourceType.HLS
                    "dash" -> VideoSourceType.DASH
                    else -> VideoSourceType.DIRECT
                }
                out.add(VideoSource(url, "", type))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun sortSources(sources: List<VideoSource>): List<VideoSource> {
        return sources.distinctBy { it.url }.sortedWith(
            compareBy<VideoSource> {
                when (it.type) {
                    VideoSourceType.HLS -> 0
                    VideoSourceType.DIRECT -> 1
                    VideoSourceType.DASH -> 2
                }
            }.thenByDescending { it.quality }
        )
    }
}
