package com.livetv.tv.legacy

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.livetv.tv.R
import com.tencent.smtt.sdk.WebView


class TbsDebugActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "TbsDebugActivity"
        private const val DEBUG_URL = "https://debugtbs.qq.com"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tbs_debug)
        val webView = findViewById<WebView>(R.id.webView)
        webView.settings.javaScriptEnabled = true
        Toast.makeText(this, "点击页面的“安装线上内核”安装后，手动关闭APP重开", Toast.LENGTH_LONG).show()
        webView.loadUrl(DEBUG_URL)
    }

    override fun onDestroy() {
        super.onDestroy()
        findViewById<WebView>(R.id.webView)?.apply {
            stopLoading()
            clearCache(true)
            destroy()
        }
    }
}