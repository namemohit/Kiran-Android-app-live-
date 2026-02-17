package com.example.broadcastcamera.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.broadcastcamera.R
import com.example.broadcastcamera.control.RemoteInputService
import com.example.broadcastcamera.service.ScreenCaptureService
import com.example.broadcastcamera.net.WifiController
import com.example.broadcastcamera.video.H264Encoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ScreenStreamingActivity : AppCompatActivity() {
    private lateinit var projectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private lateinit var wifiController: WifiController
    private var encoder: H264Encoder? = null
    private var isStreaming = false

    private lateinit var statusText: TextView
    private lateinit var ipInput: EditText
    private lateinit var btnManualConnect: Button
    private lateinit var accessibilityStatus: TextView
    private lateinit var btnOpenAccessibility: Button
    
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen_stream)

        statusText = findViewById(R.id.statusText)
        ipInput = findViewById(R.id.ipInput)
        btnManualConnect = findViewById(R.id.btnManualConnect)
        accessibilityStatus = findViewById(R.id.accessibilityStatus)
        btnOpenAccessibility = findViewById(R.id.btnOpenAccessibility)

        btnOpenAccessibility.setOnClickListener {
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Find 'Kiran Remote Input' and turn it ON", Toast.LENGTH_LONG).show()
        }

        wifiController = WifiController(this)
        // Use unique service name for Remote Control
        wifiController.setServiceConfig("KiranRemote", "_broadcastcam._tcp.")
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        btnManualConnect.setOnClickListener {
            val ip = ipInput.text.toString().trim()
            if (ip.isNotEmpty()) {
                statusText.text = "Status: Connecting to $ip..."
                wifiController.connectToIp(ip, 8888) {
                    onRemoteConnected()
                }
            } else {
                Toast.makeText(this, "Please enter an IP address", Toast.LENGTH_SHORT).show()
            }
        }

        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE)

        setupWifiListener()
        startAccessibilityCheck()
    }

    private fun startAccessibilityCheck() {
        mainHandler.post(object : Runnable {
            override fun run() {
                val isActive = RemoteInputService.instance != null
                if (isActive) {
                    accessibilityStatus.text = "Accessibility: Active (Ready to Control)"
                    accessibilityStatus.setTextColor(android.graphics.Color.GREEN)
                    btnOpenAccessibility.visibility = android.view.View.GONE
                } else {
                    accessibilityStatus.text = "Accessibility: DISABLED (Cannot Control)"
                    accessibilityStatus.setTextColor(android.graphics.Color.RED)
                    btnOpenAccessibility.visibility = android.view.View.VISIBLE
                }
                mainHandler.postDelayed(this, 2000)
            }
        })
    }

    private fun setupWifiListener() {
        wifiController.setClientListener(object : WifiController.OnDataReceivedListener {
            override fun onDataReceived(type: Byte, data: ByteArray) {
                when (type) {
                    3.toByte() -> { // Handshake ACK
                        Log.d("ScreenStream", "ACK received from Controller")
                        runOnUiThread { statusText.text = "Status: Remote Access Active" }
                    }
                    5.toByte() -> { // Touch Event
                        handleRemoteTouch(data)
                    }
                }
            }
        })
        
        // Auto-discover the Remote App
        wifiController.discoverAndConnect {
            onRemoteConnected()
        }
    }

    private fun onRemoteConnected() {
        Log.d("ScreenStream", "Connected to Remote! Sending handshake...")
        runOnUiThread { statusText.text = "Status: Handshaking..." }
        sendHandshake()
    }

    private fun sendHandshake() {
        wifiController.sendData(2.toByte(), "REMOTE".toByteArray())
    }

    private fun handleRemoteTouch(data: ByteArray) {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val action = buffer.int
        val normX = buffer.float
        val normY = buffer.float

        RemoteInputService.instance?.performTouch(action, normX, normY) ?: run {
            Log.w("ScreenStream", "RemoteInputService not active! Please enable in Settings.")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE && resultCode == Activity.RESULT_OK && data != null) {
            // Android 14+ requires starting foreground service BEFORE getting MediaProjection
            // We pass the data to the service and let it initialize the projection after startForeground
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            
            // Give the service a moment to enter foreground and obtain projection
            Handler(Looper.getMainLooper()).postDelayed({
                mediaProjection = ScreenCaptureService.mediaProjection
                if (mediaProjection != null) {
                    startStreaming()
                } else {
                    Log.e("ScreenStream", "Failed to obtain MediaProjection from service")
                    Toast.makeText(this, "Failed to start Screen Capture", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }, 500)
        } else {
            Toast.makeText(this, "Screen Capture Permission Denied", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun startStreaming() {
        val dm = resources.displayMetrics
        val width = 1080 // Standard streaming res
        val height = 1920
        val density = dm.densityDpi

        encoder = H264Encoder(width, height, 5000000)
        if (encoder?.start() == true) {
            isStreaming = true
            
            // Set up virtual display from MediaProjection to Encoder Surface
            val surface = encoder?.getInputSurface()
            
            // Android 14+ requires registering a callback before createVirtualDisplay
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d("ScreenStream", "MediaProjection stopped")
                    isStreaming = false
                }
            }, Handler(Looper.getMainLooper()))

            mediaProjection?.createVirtualDisplay(
                "ScreenCapture", width, height, density,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null
            )

            encoder?.setListener(object : H264Encoder.OnDataListener {
                override fun onDataEncoded(data: ByteArray) {
                    wifiController.sendData(1.toByte(), data)
                }
            })

            // Send metadata (Type 4)
            val meta = "$width,$height,0"
            wifiController.sendData(4.toByte(), meta.toByteArray())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        encoder?.stop()
        mediaProjection?.stop()
        stopService(Intent(this, ScreenCaptureService::class.java))
        wifiController.close()
    }

    companion object {
        private const val REQUEST_CODE_SCREEN_CAPTURE = 1001
    }
}
