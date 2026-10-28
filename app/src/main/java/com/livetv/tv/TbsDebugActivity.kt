package com.livetv.tv

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tencent.smtt.export.external.TbsCoreSettings
import com.tencent.smtt.sdk.QbSdk
import com.tencent.smtt.sdk.TbsDownloader
import com.tencent.smtt.sdk.TbsListener
import com.tencent.smtt.sdk.WebChromeClient
import com.tencent.smtt.sdk.WebSettings
import com.tencent.smtt.sdk.WebView
import com.tencent.smtt.sdk.WebViewClient

class TbsDebugActivity : AppCompatActivity() {

    companion object {
        private const val DEBUG_URL = "https://debugtbs.qq.com"
        private const val TAG = "TbsDebugActivity"
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initX5()

        // 启用硬件加速
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )
        setContentView(R.layout.activity_tbs_debug)

        val webView = findViewById<WebView>(R.id.webView)
        webView.settings?.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setAppCacheEnabled(true)
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, progress: Int) {
                Log.d(TAG, "Loading progress: $progress%")
                if (progress == 100) {
                    Log.d(TAG, "Page loaded: ${view.url}")
                }
            }

            override fun onConsoleMessage(msg: com.tencent.smtt.export.external.interfaces.ConsoleMessage): Boolean {
                Log.d(TAG, "Console: ${msg.message()} [${msg.messageLevel()}]")
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                Log.d(TAG, "Page started: $url")
            }

            override fun onPageFinished(view: WebView, url: String) {
                Log.d(TAG, "Page finished: $url")
            }

            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                Log.e(TAG, "Failed to load X5 debug page: $description, code: $errorCode")
                runOnUiThread {
                    Toast.makeText(this@TbsDebugActivity, "加载X5调试页面失败：$description", Toast.LENGTH_LONG).show()
                }
            }

            override fun onReceivedHttpError(view: WebView, request: com.tencent.smtt.export.external.interfaces.WebResourceRequest, response: com.tencent.smtt.export.external.interfaces.WebResourceResponse) {
                Log.e(TAG, "HTTP error: ${response.statusCode}, URL: ${request.url}")
                runOnUiThread {
                    Toast.makeText(this@TbsDebugActivity, "HTTP错误：${response.statusCode}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onReceivedSslError(view: WebView, handler: com.tencent.smtt.export.external.interfaces.SslErrorHandler, error: com.tencent.smtt.export.external.interfaces.SslError) {
                Log.w(TAG, "SSL error: ${error.primaryError}")
                handler.proceed()
            }
        }

        // 添加 JavaScript 接口
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun notify(message: String) {
                Log.d(TAG, "JavaScript message: $message")
            }
        }, "main")

        // 设置下载监听
        QbSdk.setTbsListener(object : TbsListener {
            override fun onDownloadProgress(progress: Int) {
                Log.d(TAG, "X5内核下载进度: $progress%")
                runOnUiThread {
                    Toast.makeText(this@TbsDebugActivity, "下载进度: $progress%", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onInstallFinish(status: Int) {
                Log.d(TAG, "X5内核安装完成，状态码: $status")
                runOnUiThread {
                    if (status == 200) {
                        Toast.makeText(this@TbsDebugActivity, "X5内核安装成功，即将重启应用", Toast.LENGTH_LONG).show()
                        handler.postDelayed({
                            val restartIntent = Intent(this@TbsDebugActivity, com.livetv.tv.MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            }
                            startActivity(restartIntent)
                            finish()
                        }, 2000L)
                    } else {
                        Toast.makeText(this@TbsDebugActivity, "X5内核安装失败，状态码: $status", Toast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onDownloadFinish(status: Int) {
                Log.d(TAG, "X5内核下载完成，状态码: $status")
                runOnUiThread {
                    if (status != 100) {
                        val errorMsg = when (status) {
                            101 -> "网络连接失败"
                            102 -> "存储空间不足"
                            103 -> "下载被用户取消"
                            else -> "未知错误，状态码: $status"
                        }
                        Toast.makeText(this@TbsDebugActivity, "X5内核下载失败：$errorMsg", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })

        val settings = mutableMapOf<String, Any>(
            TbsCoreSettings.TBS_SETTINGS_USE_SPEEDY_CLASSLOADER to true,
            TbsCoreSettings.TBS_SETTINGS_USE_DEXLOADER_SERVICE to true
        )
        // 检查 X5 内核状态并启动下载
        if (!YourTVApplication.getInstance().isX5Available()) {
            Log.w(TAG, "X5 kernel not available, initializing...")
            QbSdk.setDownloadWithoutWifi(true)
            TbsDownloader.startDownload(this)
            QbSdk.initTbsSettings(settings)
            QbSdk.initX5Environment(this, object : QbSdk.PreInitCallback {
                override fun onCoreInitFinished() {
                    Log.d(TAG, "X5 Core initialized")
                }
                override fun onViewInitFinished(isX5Loaded: Boolean) {
                    Log.d(TAG, "X5 WebView initialized: $isX5Loaded")
                    if (!isX5Loaded) {
                        runOnUiThread {
                            Toast.makeText(this@TbsDebugActivity, "X5内核加载失败，请检查网络", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            })
        } else {
            runOnUiThread {
                Toast.makeText(this, "X5内核已初始化，无需下载", Toast.LENGTH_SHORT).show()
            }
        }

        try {
            webView.loadUrl("about:blank") // 清空旧页面
            webView.loadUrl(DEBUG_URL)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WebView: ${e.message}", e)
            runOnUiThread {
                Toast.makeText(this, "X5 WebView 初始化失败：${e.message}", Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    private fun initX5() {
        // 设置X5内核优化参数
        val settings = mutableMapOf<String, Any>(
            TbsCoreSettings.TBS_SETTINGS_USE_SPEEDY_CLASSLOADER to true,
            TbsCoreSettings.TBS_SETTINGS_USE_DEXLOADER_SERVICE to true
        )
        QbSdk.initTbsSettings(settings)
        QbSdk.initX5Environment(this, object : QbSdk.PreInitCallback {
            override fun onCoreInitFinished() {
                Log.i(TAG, "X5 Core initialized")
            }

            override fun onViewInitFinished(isX5: Boolean) {
                Log.i(TAG, "X5 View initialized, isX5=$isX5")
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        findViewById<WebView>(R.id.webView)?.destroy()
    }
}