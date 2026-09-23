package com.mingkun.pdfviewer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var web: WebView
    private var downloadId: Long = -1
    private var pendingInstall = false
    private val currentPdf by lazy { File(cacheDir, "current.pdf") }
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingUtter: Pair<String, String>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "AppBridge")
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileCallback = filePathCallback
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = "*/*"
                intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/pdf",
                    "application/epub+zip",
                    "text/*",
                    "application/csv",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "image/*"
                ))
                startActivityForResult(intent, 1001)
                return true
            }
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/viewer.html")
        registerReceiver(
            onDownloadComplete,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        )
    }

    @Volatile private var ttsGender = "default"

    private val onDownloadComplete = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == downloadId && downloadId >= 0) {
                if (!packageManager.canRequestPackageInstalls()) {
                    pendingInstall = true
                    jsCall("window.onUpdateDownload && window.onUpdateDownload('permission')")
                    try {
                        val s = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        s.data = Uri.parse("package:" + packageName)
                        startActivity(s)
                    } catch (e: Exception) {
                    }
                    return
                }
                fireInstall()
            }
        }
    }

    private fun fireInstall() {
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val uri = dm.getUriForDownloadedFile(downloadId)
        if (uri != null) {
            val install = Intent(Intent.ACTION_VIEW)
            install.setDataAndType(uri, "application/vnd.android.package-archive")
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                startActivity(install)
                jsCall("window.onUpdateDownload && window.onUpdateDownload('done')")
                return
            } catch (e: Exception) {
            }
        }
        jsCall("window.onUpdateDownload && window.onUpdateDownload('fail')")
    }

    override fun onResume() {
        super.onResume()
        if (pendingInstall) {
            pendingInstall = false
            if (packageManager.canRequestPackageInstalls() && downloadId >= 0) {
                jsCall("window.onUpdateDownload && window.onUpdateDownload('installing')")
                fireInstall()
            }
        }
    }

    private fun jsCall(code: String) {
        runOnUiThread { web.evaluateJavascript(code, null) }
    }

    private fun currentVersionCode(): Int {
        val info = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) {
            (info.longVersionCode and 0x7FFFFFFFL).toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun appVersion(): String {
            val info = packageManager.getPackageInfo(packageName, 0)
            return (info.versionName ?: "?") + " (" + currentVersionCode() + ")"
        }

        @JavascriptInterface
        fun checkUpdate() {
            thread {
                var result = JSONObject()
                try {
                    val conn = URL(VERSION_URL).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val obj = JSONObject(body)
                    val remoteCode = obj.optInt("versionCode", 0)
                    result = JSONObject()
                    result.put("hasUpdate", remoteCode > currentVersionCode())
                    result.put("versionName", obj.optString("versionName", ""))
                    result.put("versionCode", remoteCode)
                    result.put("apkUrl", obj.optString("apkUrl", ""))
                } catch (e: Exception) {
                    result = JSONObject()
                    result.put("error", e.message ?: "network error")
                }
                jsCall("window.onUpdateInfo && window.onUpdateInfo(" + result.toString() + ")")
            }
        }

        @JavascriptInterface
        fun downloadUpdate(apkUrl: String) {
            try {
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                val req = DownloadManager.Request(Uri.parse(apkUrl))
                req.setTitle("PDF 阅读器更新")
                req.setMimeType("application/vnd.android.package-archive")
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                req.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, "pdfviewer-update.apk"
                )
                downloadId = dm.enqueue(req)
                jsCall("window.onUpdateDownload && window.onUpdateDownload('start')")
            } catch (e: Exception) {
                jsCall("window.onUpdateDownload && window.onUpdateDownload('fail')")
            }
        }

        @JavascriptInterface
        fun printPdf() {
            if (!currentPdf.exists()) {
                jsCall("window.onPrintResult && window.onPrintResult('nofile')")
                return
            }
            runOnUiThread {
                try {
                    val pm = getSystemService(PRINT_SERVICE) as PrintManager
                    pm.print(
                        "PDF 阅读器打印",
                        PdfPrintAdapter(currentPdf),
                        PrintAttributes.Builder().build()
                    )
                    jsCall("window.onPrintResult && window.onPrintResult('ok')")
                } catch (e: Exception) {
                    jsCall("window.onPrintResult && window.onPrintResult('fail')")
                }
            }
        }

        @JavascriptInterface
        fun openExternal(url: String) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
            }
        }

        @JavascriptInterface
        fun setVoiceGender(g: String) { ttsGender = g }

        @JavascriptInterface
        fun ttsSpeak(text: String, id: String) {
            if (ttsReady && tts != null) {
                speakNow(text, id)
                return
            }
            pendingUtter = text to id
            if (tts == null) {
                tts = TextToSpeech(applicationContext) { status ->
                    ttsReady = status == TextToSpeech.SUCCESS
                    runOnUiThread {
                        if (ttsReady && tts != null) {
                            tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                override fun onStart(utteranceId: String?) {}
                                override fun onDone(utteranceId: String?) {
                                    jsCall("window.onTtsDone && window.onTtsDone()")
                                }
                                override fun onError(utteranceId: String?) {
                                    jsCall("window.onTtsDone && window.onTtsDone()")
                                }
                            })
                            pendingUtter?.let { speakNow(it.first, it.second) }
                        } else {
                            jsCall("window.onTtsFail && window.onTtsFail()")
                        }
                        pendingUtter = null
                    }
                }
            }
        }

        @JavascriptInterface
        fun ttsStop() {
            try { tts?.stop() } catch (e: Exception) {}
        }
    }

    private fun pickVoice(t: TextToSpeech, zh: Boolean, female: Boolean): android.speech.tts.Voice? {
        return try {
            val voices = t.voices ?: return null
            val femaleRe = Regex("huihui|xiaoxiao|yaoyao|zira|aria|jenny|female|女", RegexOption.IGNORE_CASE)
            val maleRe = Regex("kangkang|yunxi|yunyang|david|mark|george|guy|male|男", RegexOption.IGNORE_CASE)
            val re = if (female) femaleRe else maleRe
            val langPrefix = if (zh) "zh" else "en"
            val byLang = voices.filter { v ->
                v.locale.language.equals(langPrefix, true) || (zh && v.locale.language.equals("cmn", true))
            }
            val source = if (byLang.isNotEmpty()) byLang else voices
            source.filter { re.containsMatchIn(it.name) }.maxByOrNull { it.quality }
        } catch (e: Exception) {
            null
        }
    }

    private fun speakNow(text: String, id: String) {
        val t = tts ?: return
        val zh = text.any { it.code in 0x4E00..0x9FFF }
        try {
            t.language = if (zh) java.util.Locale.CHINA else java.util.Locale.US
            t.setSpeechRate(1.0f)
            if (ttsGender != "default") {
                val picked = pickVoice(t, zh, ttsGender == "female")
                if (picked != null) {
                    t.voice = picked
                    t.setPitch(1.0f)
                } else {
                    t.setPitch(if (ttsGender == "female") 1.25f else 0.8f)
                }
            } else {
                t.setPitch(1.0f)
            }
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        } catch (e: Exception) {
            jsCall("window.onTtsDone && window.onTtsDone()")
        }
    }

    inner class PdfPrintAdapter(private val file: File) : PrintDocumentAdapter() {
        private var printAttrs: PrintAttributes? = null

        private fun countPages(): Int {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r -> return r.pageCount }
            }
        }

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            try {
                printAttrs = newAttributes
                val info = PrintDocumentInfo.Builder("pdfviewer.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(countPages())
                    .build()
                callback.onLayoutFinished(info, true)
            } catch (e: Exception) {
                callback.onLayoutFailed(e.message)
            }
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback
        ) {
            try {
                val out = FileOutputStream(destination.fileDescriptor)
                val attrs = printAttrs ?: PrintAttributes.Builder()
                    .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                    .build()
                val doc = PrintedPdfDocument(this@MainActivity, attrs)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        for (i in 0 until renderer.pageCount) {
                            if (cancellationSignal?.isCanceled == true) {
                                doc.close()
                                callback.onWriteCancelled()
                                return
                            }
                            renderer.openPage(i).use { page ->
                                val vw = page.width * 2
                                val vh = page.height * 2
                                val bmp = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val p = doc.startPage(i + 1)
                                val cw = p.canvas.width.toFloat()
                                val chh = p.canvas.height.toFloat()
                                val s = Math.min(cw / bmp.width, chh / bmp.height)
                                val dx = (cw - bmp.width * s) / 2f
                                val dy = (chh - bmp.height * s) / 2f
                                p.canvas.drawBitmap(
                                    bmp, null,
                                    RectF(dx, dy, dx + bmp.width * s, dy + bmp.height * s), null
                                )
                                doc.finishPage(p)
                                bmp.recycle()
                            }
                        }
                    }
                }
                doc.writeTo(out)
                doc.close()
                out.close()
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1001) {
            val cb = fileCallback
            fileCallback = null
            if (resultCode == RESULT_OK && data?.data != null) {
                try {
                    contentResolver.openInputStream(data.data!!)!!.use { input ->
                        currentPdf.outputStream().use { input.copyTo(it) }
                    }
                } catch (e: Exception) {
                }
                cb?.onReceiveValue(arrayOf(data.data!!))
            } else {
                cb?.onReceiveValue(null)
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        try { tts?.shutdown() } catch (e: Exception) {}
        try {
            unregisterReceiver(onDownloadComplete)
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    companion object {
        private const val VERSION_URL =
            "https://5130599.best/Translator/downloads/pdfviewer-version.json"
    }
}
