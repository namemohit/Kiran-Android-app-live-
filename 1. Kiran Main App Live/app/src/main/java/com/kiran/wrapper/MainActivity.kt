package com.kiran.wrapper

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val webAppUrl = "https://kiran.onegodown.com"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)

        // Configure WebView settings for a native-like experience
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = true
            allowContentAccess = true
            userAgentString = webView.settings.userAgentString + " KiranApp/1.0"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                // Keep the user in the app if the URL is within the target domain
                return if (url.contains("onegodown.com")) {
                    false
                } else {
                    // Let the system handle other URLs (e.g., mailto, external links)
                    // If you want to force external browser, you'd use an Intent here.
                    false
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Could show a loading indicator here
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Hide loading indicator
            }
        }

        webView.loadUrl(webAppUrl)

        val fabOptions: View = findViewById(R.id.fabOptions)
        
        var dX = 0f
        var dY = 0f
        var startRawX = 0f
        var startRawY = 0f
        var startTime = 0L

        fabOptions.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startTime = System.currentTimeMillis()
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    view.x = event.rawX + dX
                    view.y = event.rawY + dY
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - startTime
                    val distanceSize = Math.hypot((event.rawX - startRawX).toDouble(), (event.rawY - startRawY).toDouble())
                    
                    // If moved less than 10 pixels and released within 200ms, it's a click
                    if (distanceSize < 20 && duration < 300) {
                        view.performClick()
                    }
                }
                else -> return@setOnTouchListener false
            }
            true
        }

        fabOptions.setOnClickListener {
            CameraOptionsSheet().show(supportFragmentManager, CameraOptionsSheet.TAG)
        }

        // Handle back button navigation
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    // If no history, finish the activity or maybe confirm exit
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }
}
