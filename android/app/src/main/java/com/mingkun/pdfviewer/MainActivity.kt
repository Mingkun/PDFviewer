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
    private val REQ_OPEN = 2001
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
        // 方案A：系统导航栏配色与应用深色底一致（避免底部出现白块），图标用浅色
        try {
            window.navigationBarColor = android.graphics.Color.parseColor("#1e2126")
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                window.decorView.systemUiVisibility =
                    window.decorView.systemUiVisibility and android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        } catch (_: Exception) {}
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                java.io.File(getExternalFilesDir(null), "crash.txt").writeText(
                    "time=" + System.currentTimeMillis() + "\nthread=" + t.name + "\n" + android.util.Log.getStackTraceString(e))
            } catch (_: Exception) {}
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        val lastCrash = try {
            val cf = java.io.File(getExternalFilesDir(null), "crash.txt")
            if (cf.isFile) cf.readText().also { cf.delete() } else null
        } catch (_: Exception) { null }
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
                    "application/msword",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "image/*",
                    "audio/*",
                    "video/*"
                ))
                startActivityForResult(intent, 1001)
                return true
            }
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/viewer.html")
        if (lastCrash != null) {
            val esc = lastCrash.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            web.loadDataWithBaseURL(null, "<html><body style='font-family:monospace;padding:16px;font-size:12px;white-space:pre-wrap;line-height:1.5'><h3>PDF查看 v3.24 上次启动崩溃现场</h3><p style='color:#b02a37'>请截图发给机器人排查</p><hr>" + esc.take(6000) + "</body></html>", "text/html", "utf-8", null)
        }
        try {
            val dlFilter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(onDownloadComplete, dlFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(onDownloadComplete, dlFilter)
        } catch (_: Exception) {
        }
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

    private fun installApk(file: java.io.File) {
        try {
            if (!packageManager.canRequestPackageInstalls()) {
                pendingInstall = true
                jsCall("window.onUpdateDownload && window.onUpdateDownload('permission')")
                try {
                    val s = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    s.data = Uri.parse("package:" + packageName)
                    startActivity(s)
                } catch (e: Exception) {}
                return
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val install = Intent(Intent.ACTION_VIEW)
            install.setDataAndType(uri, "application/vnd.android.package-archive")
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(install)
            jsCall("window.onUpdateDownload && window.onUpdateDownload('done')")
        } catch (e: Exception) {
            jsCall("window.onUpdateDownload && window.onUpdateDownload('fail')")
            android.widget.Toast.makeText(this, "安装失败: " + (e.message ?: "?"), android.widget.Toast.LENGTH_LONG).show()
        }
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

    private fun recentPrefs() = getSharedPreferences("pv_recent", MODE_PRIVATE)

    private fun loadRecentJson(): String =
        try { recentPrefs().getString("list", "[]") ?: "[]" } catch (_: Exception) { "[]" }

    private fun saveRecentUri(uri: Uri, name: String) {
        try {
            val arr = org.json.JSONArray(loadRecentJson())
            val out = org.json.JSONArray()
            out.put(org.json.JSONObject().put("uri", uri.toString()).put("name", name).put("time", System.currentTimeMillis()))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("uri") == uri.toString()) continue
                if (out.length() >= 20 && !o.optBoolean("pin")) continue
                out.put(o)
            }
            recentPrefs().edit().putString("list", out.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun saveRecentPath(path: String, name: String) {
        try {
            val arr = org.json.JSONArray(loadRecentJson())
            val out = org.json.JSONArray()
            out.put(org.json.JSONObject().put("path", path).put("name", name).put("time", System.currentTimeMillis()))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("path") == path) continue
                // 同名且无路径的旧 URI 记录 → 清理（同一文件重新打开）
                if (o.optString("name") == name && o.optString("path").isEmpty()) continue
                if (out.length() >= 20 && !o.optBoolean("pin")) continue
                out.put(o)
            }
            recentPrefs().edit().putString("list", out.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun readFileAndSend(f: java.io.File, name: String, key: String) {
        Thread {
            try {
                val bytes = f.readBytes()
                runOnUiThread { sendFileToPage(name, key, bytes) }
            } catch (_: Exception) {
                runOnUiThread { jsCall("window.onRecentFail && window.onRecentFail(" + jsonq(name) + ")") }
            }
        }.start()
    }

    private fun findFileByName(name: String): java.io.File? {
        try {
            if (name.isEmpty()) return null
            val ext = name.substringAfterLast('.', "").lowercase()
            val queue = ArrayDeque<java.io.File>()
            queue.add(java.io.File("/storage/emulated/0"))
            try {
                java.io.File("/storage").listFiles()?.forEach { d ->
                    if (d.isDirectory && d.name != "emulated" && d.name != "self") queue.add(d)
                }
            } catch (_: Exception) {}
            val skip = setOf("Android", "LOST.DIR", "obb", ".thumbnails", "cache")
            var count = 0
            while (queue.isNotEmpty() && count < 60000) {
                val dir = queue.removeFirst()
                val files = dir.listFiles() ?: continue
                for (f in files) {
                    count++
                    if (f.isDirectory) { if (f.name !in skip && !f.name.startsWith(".")) queue.add(f); continue }
                    if (ext.isNotEmpty() && f.extension.lowercase() != ext) continue
                    if (f.name == name) return f
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun displayNameOf(uri: Uri): String {
        var nm: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) nm = c.getString(idx)
            }
        } catch (_: Exception) {}
        if (nm.isNullOrBlank()) nm = uri.lastPathSegment ?: "文件"
        return nm!!
    }

    private fun jsonq(v: String?): String = org.json.JSONObject.quote(v ?: "")

    fun readUriAndSend(uri: Uri, name: String, retryWithPermission: Boolean) {
        Thread {
            var sent = false
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) { sent = true; runOnUiThread { sendFileToPage(name, uri.toString(), bytes) } }
            } catch (_: Exception) {}
            if (!sent && retryWithPermission) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) { sent = true; runOnUiThread { sendFileToPage(name, uri.toString(), bytes) } }
                } catch (_: Exception) {}
            }
            if (!sent) runOnUiThread { jsCall("window.onRecentFail && window.onRecentFail(" + jsonq(name) + ")") }
        }.start()
    }

    private fun sendFileToPage(name: String, key: String, bytes: ByteArray) {
        jsCall("window.onFileStart && window.onFileStart(" + jsonq(name) + "," + jsonq(key) + "," + bytes.size + ")")
        val chunk = 384 * 1024
        var off = 0
        var seq = 0
        while (off < bytes.size) {
            val n = minOf(chunk, bytes.size - off)
            val b64 = android.util.Base64.encodeToString(bytes, off, n, android.util.Base64.NO_WRAP)
            jsCall("window.onFileChunk && window.onFileChunk(" + seq + "," + jsonq(b64) + ")")
            off += n
            seq++
        }
        jsCall("window.onFileEnd && window.onFileEnd()")
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
        fun hasStoragePermission(): Boolean {
            return if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.os.Environment.isExternalStorageManager()
            } else {
                checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        }

        @JavascriptInterface
        fun requestStoragePermission() {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:$packageName"))
                    startActivity(intent)
                } else {
                    requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 100)
                }
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun searchFiles(ext: String): String {
            try {
                if (!hasStoragePermission()) return "[{\"n\":\"__NEED_PERM__\"}]"
                val exts = ext.lowercase().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                val audioE = setOf("mp3", "wav", "ogg", "m4a", "flac", "aac", "wma", "opus")
                val videoE = setOf("mp4", "webm", "mkv", "avi", "mov", "3gp", "m4v", "ts", "flv")
                val checkMedia = exts.isEmpty() || exts.any { audioE.contains(it) || videoE.contains(it) }
                val results = org.json.JSONArray()
                val queue = ArrayDeque<java.io.File>()
                // 扫描所有存储根：内部 + 外置SD
                queue.add(java.io.File("/storage/emulated/0"))
                try {
                    java.io.File("/storage").listFiles()?.forEach { d ->
                        if (d.isDirectory && d.name != "emulated" && d.name != "self") queue.add(d)
                    }
                } catch (_: Exception) {}
                val skipDirs = setOf("Android", "LOST.DIR", "obb", ".thumbnails", "cache")
                var count = 0
                while (queue.isNotEmpty() && count < 100000) {
                    val dir = queue.removeFirst()
                    val files = dir.listFiles() ?: continue
                    for (f in files) {
                        if (f.name.startsWith(".")) continue
                        if (f.isDirectory) { if (f.name !in skipDirs) queue.add(f); continue }
                        val fe = f.extension.lowercase()
                        if (exts.isNotEmpty() && !exts.contains(fe)) continue
                        // 媒体文件：读文件头验证真实格式（过滤加密/损坏文件）
                        val enc = isEncryptedMedia(f)
                        val o = org.json.JSONObject()
                        o.put("n", f.name); o.put("d", false)
                        o.put("enc", enc)
                        o.put("qq", isQQEncFile(f))
                        o.put("s", f.length()); o.put("t", f.lastModified()); o.put("p", f.absolutePath)
                        results.put(o); count++
                    }
                }
                return results.toString()
            } catch (_: Exception) { return "[]" }
        }

        private fun isEncryptedMedia(f: java.io.File): Boolean {
            // 只认【明确的加密文件签名】；不认识的格式一律不算加密（避免误标正常音频）
            try {
                java.io.FileInputStream(f).use { fis ->
                    val h = ByteArray(16)
                    val n = fis.read(h)
                    if (n < 4) return false
                    val m8 = if (n >= 8) String(h, 0, 8, Charsets.ISO_8859_1) else ""
                    val m4 = String(h, 0, 4, Charsets.ISO_8859_1)
                    if (m8 == "CTENFDAM") return true      // 网易云 .ncm
                    if (m4 == "QMCK" || m4 == "QMCc") return true // QQ音乐 qmc 变体
                    return false
                }
            } catch (_: Exception) { return false }
        }

        private fun isQQEncFile(f: java.io.File): Boolean {
            // QQ音乐加密文件的可靠判据：文件末 8 字节内含 "QTag"/"STag" 尾部标记
            try {
                val len = f.length()
                if (len < 8L) return false
                java.io.RandomAccessFile(f, "r").use { raf ->
                    raf.seek(len - 8)
                    val b = ByteArray(8)
                    if (raf.read(b) < 8) return false
                    val s = String(b, Charsets.ISO_8859_1)
                    return s.contains("QTag") || s.contains("STag")
                }
            } catch (_: Exception) { return false }
        }

        @JavascriptInterface
        fun listFiles(path: String): String {
            try {
                if (!hasStoragePermission()) return "[]"
                val dir = if (path.isEmpty()) java.io.File("/storage/emulated/0") else java.io.File(path)
                if (!dir.exists() || !dir.isDirectory) return "[]"
                val items = dir.listFiles()?.sortedWith(compareByDescending<java.io.File> { it.isDirectory }.thenByDescending { it.lastModified() }) ?: emptyList()
                val arr = org.json.JSONArray()
                val mediaE = setOf("mp3","wav","ogg","m4a","flac","aac","wma","opus","mp4","webm","mkv","avi","mov","3gp","m4v","ts","flv")
                for (f in items.take(500)) {
                    if (f.name.startsWith(".")) continue
                    val o = org.json.JSONObject()
                    o.put("n", f.name)
                    o.put("d", f.isDirectory)
                    o.put("s", if (f.isFile) f.length() else 0)
                    o.put("t", f.lastModified())
                    o.put("p", f.absolutePath)
                    o.put("enc", if (f.isFile) isEncryptedMedia(f) else false)
                    o.put("qq", if (f.isFile) isQQEncFile(f) else false)
                    arr.put(o)
                }
                return arr.toString()
            } catch (_: Exception) { return "[]" }
        }

        @JavascriptInterface
        fun readFile(path: String): String {
            try {
                val f = java.io.File(path)
                if (!f.exists() || f.length() > 100 * 1024 * 1024) return ""
                val bytes = f.readBytes()
                return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            } catch (_: Exception) { return "" }
        }

        @JavascriptInterface
        fun openFile() {
            try {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.type = "*/*"
                i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/pdf", "application/epub+zip", "text/*", "application/csv",
                    "application/msword", "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "image/*", "audio/*", "video/*"))
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                startActivityForResult(i, REQ_OPEN)
            } catch (_: Exception) {
                jsCall("window.onRecentFail && window.onRecentFail('')")
            }
        }

        @JavascriptInterface
        fun getRecent(): String = loadRecentJson()

        @JavascriptInterface
        fun vibrate(ms: Int) {
            try {
                val v = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
                val timings = longArrayOf(0, 150, 80, 150)
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    v.vibrate(android.os.VibrationEffect.createWaveform(timings, -1))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(timings, -1)
                }
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun pinRecent(index: Int, pinned: Boolean) {
            try {
                val arr = org.json.JSONArray(loadRecentJson())
                val o = arr.optJSONObject(index) ?: return
                o.put("pin", pinned)
                recentPrefs().edit().putString("list", arr.toString()).apply()
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun openRecent(index: Int) {
            try {
                val arr = org.json.JSONArray(loadRecentJson())
                val o = arr.optJSONObject(index)
                if (o == null) { jsCall("window.onRecentFail && window.onRecentFail('')"); return }
                val nm = o.optString("name")
                val path = o.optString("path")
                val uriStr = o.optString("uri")
                Thread {
                    var sent = false
                    // ① 按路径（自绘浏览器记录）
                    if (path.isNotEmpty()) {
                        val fs = java.io.File(path)
                        if (fs.exists() && fs.isFile) {
                            try {
                                val bytes = fs.readBytes()
                                saveRecentPath(path, nm)
                                runOnUiThread { sendFileToPage(nm, path, bytes) }
                                sent = true
                            } catch (_: Exception) {}
                        }
                    }
                    // ② 按 URI
                    if (!sent && uriStr.isNotEmpty()) {
                        try {
                            val uri = Uri.parse(uriStr)
                            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            if (bytes != null) { runOnUiThread { sendFileToPage(nm, uriStr, bytes) }; sent = true }
                        } catch (_: Exception) {}
                    }
                    // ③ 自愈：按文件名全盘搜索
                    if (!sent && nm.isNotEmpty()) {
                        val found = findFileByName(nm)
                        if (found != null) {
                            try {
                                val bytes = found.readBytes()
                                saveRecentPath(found.absolutePath, nm)
                                runOnUiThread { sendFileToPage(nm, found.absolutePath, bytes) }
                                sent = true
                            } catch (_: Exception) {}
                        }
                    }
                    if (!sent) runOnUiThread { jsCall("window.onRecentFail && window.onRecentFail(" + jsonq(nm) + ")") }
                }.start()
            } catch (_: Exception) {
                jsCall("window.onRecentFail && window.onRecentFail('')")
            }
        }

        @JavascriptInterface
        fun appVersion(): String {
            val info = packageManager.getPackageInfo(packageName, 0)
            return (info.versionName ?: "?") + " (" + currentVersionCode() + ")"
        }

        @JavascriptInterface
        fun savePdfBase64(name: String, b64: String) {
            try {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                val safe = if (name.endsWith(".pdf")) name else (name + ".pdf")
                val outName = "PDFviewer_" + System.currentTimeMillis() + "_" + safe
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val values = android.content.ContentValues()
                    values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, outName)
                    values.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/PDFviewer")
                    val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    runOnUiThread { android.widget.Toast.makeText(this@MainActivity, "已保存到 Download/PDFviewer/" + outName, android.widget.Toast.LENGTH_LONG).show() }
                } else {
                    val dir = java.io.File(getExternalFilesDir(null), "saved")
                    dir.mkdirs()
                    java.io.File(dir, outName).outputStream().use { it.write(bytes) }
                    runOnUiThread { android.widget.Toast.makeText(this@MainActivity, "已保存 " + outName, android.widget.Toast.LENGTH_LONG).show() }
                }
            } catch (e: Exception) {
                runOnUiThread { android.widget.Toast.makeText(this@MainActivity, "保存失败：" + (e.message ?: "?"), android.widget.Toast.LENGTH_LONG).show() }
            }
        }

        @JavascriptInterface
        fun saveFileBase64(name: String, mime: String, b64: String) {
            try {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                val outName = "PDFviewer_" + System.currentTimeMillis() + "_" + name
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val values = android.content.ContentValues()
                    values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, outName)
                    values.put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                    values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/PDFviewer")
                    val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    runOnUiThread { android.widget.Toast.makeText(this@MainActivity, "已保存到 Download/PDFviewer/" + outName, android.widget.Toast.LENGTH_LONG).show() }
                } else {
                    val dir = java.io.File(getExternalFilesDir(null), "saved")
                    dir.mkdirs()
                    java.io.File(dir, outName).outputStream().use { it.write(bytes) }
                    runOnUiThread { android.widget.Toast.makeText(this@MainActivity, "已保存 " + outName, android.widget.Toast.LENGTH_LONG).show() }
                }
            } catch (e: Exception) {
                runOnUiThread { android.widget.Toast.makeText(this@MainActivity, "保存失败：" + (e.message ?: "?"), android.widget.Toast.LENGTH_LONG).show() }
            }
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
            jsCall("window.onUpdateDownload && window.onUpdateDownload('start')")
            Thread {
                try {
                    val url = java.net.URL(apkUrl)
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 60000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    val file = java.io.File(getExternalFilesDir(null), "pdfviewer-update.apk")
                    val output = java.io.FileOutputStream(file)
                    val input = conn.inputStream
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) output.write(buf, 0, n)
                    output.close(); input.close(); conn.disconnect()
                    runOnUiThread { installApk(file) }
                } catch (e: Exception) {
                    runOnUiThread {
                        jsCall("window.onUpdateDownload && window.onUpdateDownload('fail')")
                        android.widget.Toast.makeText(this@MainActivity, "下载失败: " + (e.message ?: "?"), android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
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
                            try { tts?.shutdown() } catch (_: Exception) {}
                            tts = null
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

    private fun ttsFail(msg: String) {
        try { tts?.stop() } catch (_: Exception) {}
        runOnUiThread { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show() }
        jsCall("window.onTtsFail && window.onTtsFail()")
    }

    private fun speakNow(text: String, id: String) {
        val t = tts ?: return
        val zh = text.any { it.code in 0x4E00..0x9FFF }
        try {
            val lang = t.setLanguage(if (zh) java.util.Locale.CHINA else java.util.Locale.US)
            if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                ttsFail("缺少" + (if (zh) "中文" else "英文") + "语音数据：请到 系统设置→文字转语音 安装语音引擎")
                return
            }
            t.setSpeechRate(1.0f)
            if (ttsGender != "default") {
                val picked = pickVoice(t, zh, ttsGender == "female")
                if (picked != null) { t.voice = picked; t.setPitch(1.0f) }
                else t.setPitch(if (ttsGender == "female") 1.25f else 0.8f)
            } else t.setPitch(1.0f)
            val r = t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (r != TextToSpeech.SUCCESS) {
                try { t.setPitch(1.0f); t.voice = t.defaultVoice } catch (_: Exception) {}
                val r2 = t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id + "r")
                if (r2 != TextToSpeech.SUCCESS) ttsFail("语音引擎无法发声（错误码 " + r2 + "）：请检查媒体音量与文字转语音设置")
            }
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
        if (requestCode == REQ_OPEN) {
            val uri: Uri? = if (resultCode == RESULT_OK) data?.data else null
            if (uri == null) return
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            val nm = displayNameOf(uri)
            saveRecentUri(uri, nm)
            readUriAndSend(uri, nm, false)
            return
        }
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
