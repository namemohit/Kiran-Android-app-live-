package com.example.broadcastcamera.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.broadcastcamera.databinding.ActivityBroadcasterBinding
import com.example.broadcastcamera.usb.BroadcasterUsbManager
import com.example.broadcastcamera.net.WifiController
import com.example.broadcastcamera.video.H264Encoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BroadcasterActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBroadcasterBinding
    private lateinit var usbManager: BroadcasterUsbManager
    private lateinit var wifiController: WifiController
    private var encoder: H264Encoder? = null
    private var isStartingCamera = false
    private var channel: String = "usb"
    private var lastFpsTime = 0L
    private var frameCount = 0
    private var currentFps = 0
    private var lastCalculatedRotation = 0
    
    // Resolution profiles: [Name, Width, Height]
    private val resolutions = listOf(
        Triple("1080p (1920x1080)", 1920, 1080),
        Triple("720p (1280x720)", 1280, 720),
        Triple("XGA (1024x768)", 1024, 768),
        Triple("480p (848x480)", 848, 480),
        Triple("VGA (640x480)", 640, 480),
        Triple("360p (640x360)", 640, 360)
    )
    private var selectedResIndex = 0 // Default to 1080p

    private val ACTION_USB_PERMISSION = "com.example.broadcastcamera.USB_PERMISSION"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBroadcasterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        channel = intent.getStringExtra("channel") ?: "wifi"
        usbManager = BroadcasterUsbManager(this)
        wifiController = WifiController(this)

        setupUI()

        if (allPermissionsGranted()) {
            startLogic()
            
            // Background check for intent accessory
            intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)?.let {
                Thread {
                    if (usbManager.openAccessory(it)) sendHandshakeInit()
                }.start()
            }
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private fun setupUI() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val isPortrait = screenHeight > screenWidth
        
        Log.d("Broadcaster", "SetupUI: Screen=${screenWidth}x${screenHeight} (Portrait=$isPortrait)")

        val viewFinderParams = binding.viewFinder.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        val refParams = binding.centerReference.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        
        // Use 9:16 for portrait, 16:9 for landscape
        val ratio = if (isPortrait) "9:16" else "16:9"
        viewFinderParams.dimensionRatio = ratio
        refParams.dimensionRatio = ratio

        // Width and height should be 0 (match constraint)
        viewFinderParams.width = 0
        viewFinderParams.height = 0
        refParams.width = 0
        refParams.height = 0

        // Limit size to 85% of screen to leave room for controls
        viewFinderParams.matchConstraintPercentWidth = 0.85f
        viewFinderParams.matchConstraintPercentHeight = 0.85f
        refParams.matchConstraintPercentWidth = 0.85f
        refParams.matchConstraintPercentHeight = 0.85f

        binding.viewFinder.layoutParams = viewFinderParams
        binding.centerReference.layoutParams = refParams
        
        Log.d("Broadcaster", "Applying Ratio: $ratio (85% Constraints)")

        binding.refreshButton.setOnClickListener {
            restartBroadcaster()
            Toast.makeText(this, "Refreshing Connection...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun restartBroadcaster() {
        // Stop current
        encoder?.stop()
        encoder = null
        usbManager.close()
        wifiController.close()
        
        // Restart logic
        startLogic()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)?.let {
            Thread {
                if (usbManager.openAccessory(it)) sendHandshakeInit()
            }.start()
        }
    }

    private fun startLogic() {
        // Hide preview until paired
        binding.viewFinder.visibility = android.view.View.GONE
        
        // Setup Global Listener for Wi-Fi
        wifiController.setClientListener(object : WifiController.OnDataReceivedListener {
            override fun onDataReceived(type: Byte, data: ByteArray) {
                processIncomingData(type, data)
            }
        })

        if (channel == "usb") {
            binding.statusText.text = "Status: Searching..." // simplified
            performDiscovery()
            startPeriodicScan()
        } else {
            binding.statusText.text = "Status: Searching..." // simplified
            
            // Auto-discovery
            wifiController.discoverAndConnect {
                runOnUiThread {
                    binding.statusText.text = "Status: Connecting..."
                }
                sendHandshakeInit()
            }
        }
        
        // Auto-select best resolution (1080p default)
        // selectedResIndex is already set to 0 in declaration
    }

    private fun startPeriodicScan() {
        if (channel != "usb") return
        
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (channel != "usb") return
                val manager = getSystemService(Context.USB_SERVICE) as UsbManager
                if (manager.accessoryList.isNullOrEmpty()) {
                    performDiscovery()
                    handler.postDelayed(this, 3000)
                }
            }
        }, 3000)
    }

    private fun performDiscovery() {
        if (channel != "usb") return
        
        Thread {
            val manager = getSystemService(Context.USB_SERVICE) as UsbManager
            val accessories = manager.accessoryList
            val devices = manager.deviceList
            
            // Role detection: Warn if Broadcaster (Accessory) is acting as Host
            if (devices.isNotEmpty()) {
                val device = devices.values.first()
                Log.w("Broadcaster", "ROLE ERROR: Broadcaster is acting as HOST. Found: ${device.deviceName}")
                runOnUiThread {
                    binding.statusText.text = "⚠️ SETUP ERROR: Swap OTG adapter to the RECEIVER phone!"
                    binding.statusText.setTextColor(android.graphics.Color.YELLOW)
                }
            } else {
                runOnUiThread {
                    binding.statusText.setTextColor(android.graphics.Color.WHITE)
                    if (accessories.isNullOrEmpty()) {
                        binding.statusText.text = "Status: Searching..."
                    }
                }
            }

            accessories?.firstOrNull()?.let { accessory ->
                if (manager.hasPermission(accessory)) {
                    Log.d("Broadcaster", "Accessory found and permission granted, opening...")
                    if (usbManager.openAccessory(accessory)) {
                        sendHandshakeInit()
                    }
                } else {
                    Log.d("Broadcaster", "Accessory found, requesting permission...")
                    val pi = android.app.PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), android.app.PendingIntent.FLAG_IMMUTABLE)
                    manager.requestPermission(accessory, pi)
                }
            }
        }.start()
    }

    private fun sendHandshakeInit() {
        Thread {
            Log.d("Broadcaster", "Sending Handshake Init...")
            val handshakeData = "HELLO".toByteArray()
            
            // WiFi listener is already globally set in startLogic
            if (channel == "usb") {
                // Just start reading for any incoming generic data
                usbManager.startReading { type, data ->
                    processIncomingData(type, data)
                }
                usbManager.sendData(2, handshakeData)
            } else {
                wifiController.sendData(2.toByte(), handshakeData)
            }
            
            // OPTIMISTIC START: Don't wait for reply. Assume connection is good.
            // This fixes the issue where Sender sits at "Connecting..." if Receiver reply is lost/unsupported.
            Log.d("Broadcaster", "Optimistic Start: Starting Camera immediately...")
            onHandshakeConfirmed()
            
        }.start()
    }
    
    private fun processIncomingData(type: Byte, data: ByteArray) {
        // Log incoming data for debug, but we already started the camera.
        if (type == 3.toByte()) {
            Log.d("Broadcaster", "Handshake Confirmation received (ACK)")
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                    val acc = intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)
                    acc?.let { 
                        Log.d("Broadcaster", "Accessory Attached, checking permission...")
                        performDiscovery() 
                    }
                }
                ACTION_USB_PERMISSION -> {
                    val acc = intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.d("Broadcaster", "USB Permission result: $granted")
                    if (granted && acc != null) {
                        Thread {
                            if (usbManager.openAccessory(acc)) sendHandshakeInit()
                        }.start()
                    }
                }
            }
        }
    }
    private fun onHandshakeConfirmed() {
        runOnUiThread {
            binding.statusText.text = "Status: Connected"
            binding.viewFinder.visibility = android.view.View.VISIBLE
            Toast.makeText(this, "Connected", Toast.LENGTH_SHORT).show()
            startCamera()
        }
    }

    private fun startCamera() {
        if (isStartingCamera || encoder != null) {
            Log.d("Broadcaster", "Camera/Encoder already starting or running, skipping")
            return
        }
        isStartingCamera = true
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                
                val rotation = binding.viewFinder.display.rotation
                
                // Determine if we need to swap dimensions for Portrait
                // If rotation is 0 (Portrait) or 180, we need 1080x1920, not 1920x1080
                val isPortrait = rotation == android.view.Surface.ROTATION_0 || rotation == android.view.Surface.ROTATION_180
                
                val encWidth = resolutions[selectedResIndex].second
                val encHeight = resolutions[selectedResIndex].third
                
                // Most sensors are 90 degrees offset.
                val displayRotationDegrees = when (rotation) {
                    android.view.Surface.ROTATION_0 -> 0
                    android.view.Surface.ROTATION_90 -> 90
                    android.view.Surface.ROTATION_180 -> 180
                    android.view.Surface.ROTATION_270 -> 270
                    else -> 0
                }
                val rotationDegrees = (90 - displayRotationDegrees + 360) % 360
                lastCalculatedRotation = rotationDegrees

                Log.d("Broadcaster", "Starting encoder for ${encWidth}x${encHeight} (Portrait=$isPortrait), rotationDegrees: $rotationDegrees")
                val uiPreview = Preview.Builder()
                    .setTargetResolution(android.util.Size(encWidth, encHeight))
                    .build().also {
                        it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                    }

                val bitrate = if (encWidth * encHeight >= 1920 * 1080) 5000000 else 2500000
                val newEncoder = H264Encoder(encWidth, encHeight, bitrate)
                if (newEncoder.start()) {
                    encoder = newEncoder
                    encoder?.setListener(object : H264Encoder.OnDataListener {
                        override fun onDataEncoded(data: ByteArray) {
                            frameCount++
                            val now = System.currentTimeMillis()
                            if (now - lastFpsTime >= 1000) {
                                currentFps = frameCount
                                frameCount = 0
                                lastFpsTime = now
                                runOnUiThread {
                                    binding.fpsText.text = "FPS: $currentFps"
                                }
                            }
                            
                            // Send Video Packet (Type 1)
                            if (channel == "usb") usbManager.sendData(1.toByte(), data) else wifiController.sendData(1.toByte(), data)

                            // Send Metadata Packet (Type 4) periodically (every ~2 seconds / 60 frames)
                            // We use a separate counter or frameCount? frameCount resets every second.
                            // Let's use a module on `System.currentTimeMillis()`? No.
                            // Simple: send every time FPS updates (once a second) is good enough.
                            if (now - lastFpsTime >= 1000) { // Actually this block resets frameCount. Let's send in the reset block.
                                // But the reset block already happened above.
                            }
                            // Let's just send every 30th frame.
                            // Global frame counter needed?
                            // No, `frameCount` resets.
                            if (frameCount == 1) {
                                val meta = "${encWidth},${encHeight},$lastCalculatedRotation"
                                val metaData = meta.toByteArray()
                                if (channel == "usb") usbManager.sendData(4.toByte(), metaData) else wifiController.sendData(4.toByte(), metaData)
                            }
                        }
                    })

                    val encoderPreview = Preview.Builder()
                        .setTargetResolution(android.util.Size(encWidth, encHeight))
                        .build()
                    
                    encoderPreview.setSurfaceProvider { request ->
                        val inputSurface = encoder?.getInputSurface()
                        if (inputSurface != null) {
                            request.provideSurface(inputSurface, ContextCompat.getMainExecutor(this)) { }
                        } else {
                            Log.e("Broadcaster", "Encoder surface is NULL in provider!")
                            request.willNotProvideSurface()
                        }
                    }

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, uiPreview, encoderPreview)
                    Log.d("Broadcaster", "Camera bound successfully and isStreaming=true")

                    // Send Resolution & Rotation Info (Type 4): "Width,Height,Rotation" String
                    val resString = "$encWidth,$encHeight,$rotationDegrees"
                    val resData = resString.toByteArray()
                    
                    if (channel == "usb") {
                        usbManager.sendData(4.toByte(), resData)
                    } else {
                        wifiController.sendData(4.toByte(), resData)
                    }

                    // Update local diagnostics
                    runOnUiThread {
                        val isRotated = rotationDegrees % 180 != 0
                        val contentAspect = if (isRotated) encHeight.toFloat() / encWidth.toFloat() else encWidth.toFloat() / encHeight.toFloat()
                        val aspectStr = String.format("%.2f", contentAspect)
                        binding.resText.text = "Res: ${encWidth}x${encHeight}"
                        binding.aspectText.text = "Aspect: $aspectStr (${if(isRotated) "P" else "L"})"
                    }
                } else {
                    Log.e("Broadcaster", "Failed to start H264Encoder, aborting camera start")
                    runOnUiThread {
                        binding.statusText.text = "Error: Hardware Encoder Failed"
                    }
                }
            } catch (e: Exception) { 
                Log.e("Broadcaster", "Binding failed", e) 
            } finally {
                isStartingCamera = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        if (channel == "usb") try { unregisterReceiver(usbReceiver) } catch (e: Exception) {}
        encoder?.stop(); usbManager.close(); wifiController.close()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
}
