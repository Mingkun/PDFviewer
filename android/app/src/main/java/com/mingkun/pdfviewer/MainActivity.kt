package com.mingkun.pdfviewer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var web: WebView
    private var downloadId: Long = -1
    private var pendingInstall = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = true
        web.settings.domStorageEnabled = true
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
                intent.type = "application/pdf"
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
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1001) {
            val cb = fileCallback
            fileCallback = null
            cb?.onReceiveValue(
                if (resultCode == RESULT_OK && data?.data != null) arrayOf(data.data!!) else null
            )
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
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
