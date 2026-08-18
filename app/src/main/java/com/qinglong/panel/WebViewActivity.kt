package com.qinglong.panel

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.qinglong.panel.databinding.ActivityWebviewBinding

class WebViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebviewBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWebView()
        loadQingLongPanel()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        webView.settings.apply {
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) showError("无法连接到青龙面板")
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                binding.progressBar.progress = newProgress
            }
        }
    }

    private fun loadQingLongPanel() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvLoadingStatus.text = "正在连接本地青龙服务..."

        Thread {
            var ready = false
            for (i in 0 until 60) {
                try {
                    java.net.Socket("127.0.0.1", 5700).use { }
                    ready = true
                    break
                } catch (_: Exception) {
                    Thread.sleep(500)
                }
            }

            runOnUiThread {
                if (ready) {
                    binding.tvLoadingStatus.text = "正在加载青龙面板..."
                    binding.webView.loadUrl("http://127.0.0.1:5700/")
                } else {
                    showError("青龙服务尚未启动，请返回主页重试")
                }
            }
        }.start()
    }

    private fun showError(message: String) {
        binding.progressBar.visibility = View.GONE
        binding.tvLoadingStatus.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.webView.canGoBack()) binding.webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        binding.webView.stopLoading()
        binding.webView.destroy()
        super.onDestroy()
    }
}
