package com.kiran.wrapper

import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kiran.wrapper.net.WifiController
import com.kiran.wrapper.video.H264Decoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import com.kiran.wrapper.usb.ReceiverUsbManager
import com.kiran.wrapper.ai.ModelManager
import com.kiran.wrapper.ai.YoloDetector
import com.kiran.wrapper.ui.OverlayView
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReceiverActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {
    private lateinit var wifiController: WifiController
    private var decoder: H264Decoder? = null
    
    private lateinit var videoTextureView: TextureView
    private lateinit var statusText: TextView
    private lateinit var resText: TextView
    private lateinit var fpsText: TextView
    private lateinit var aspectText: TextView
    private lateinit var retryButton: Button
    private lateinit var overlayView: OverlayView
    private lateinit var confSeekBar: SeekBar
    private lateinit var confLabel: TextView
    private lateinit var usbManager: ReceiverUsbManager
    private var modelManager: ModelManager? = null
    private var yoloDetector: YoloDetector? = null
    private val aiExecutor = Executors.newSingleThreadExecutor()
    private var isAiEnabled = true
    
    private var channel: String = "wifi"
    private var streamingThread: Thread? = null
    private val ACTION_USB_PERMISSION = "com.kiran.wrapper.USB_PERMISSION"

    private var frameCount = 0
    private var lastFpsTime = 0L
    private var currentFps = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receiver)

        videoTextureView = findViewById(R.id.videoTextureView)
        statusText = findViewById(R.id.statusText)
        resText = findViewById(R.id.resText)
        fpsText = findViewById(R.id.fpsText)
        aspectText = findViewById(R.id.aspectText)
        retryButton = findViewById(R.id.retryButton)
        overlayView = findViewById(R.id.overlayView)
        confSeekBar = findViewById(R.id.confSeekBar)
        confLabel = findViewById(R.id.confLabel)

        channel = intent.getStringExtra("channel") ?: "wifi"
        usbManager = ReceiverUsbManager(this)
        modelManager = ModelManager(this)
        
        initAi()

        wifiController = WifiController(this)
        videoTextureView.surfaceTextureListener = this

        retryButton.setOnClickListener {
            if (channel == "usb") performDiscovery()
            else restartReceiver()
        }

        setupSettings()

        if (allPermissionsGranted()) {
            startLogic()
        } else {
            val permissions = if (channel == "local") {
                REQUIRED_PERMISSIONS + Manifest.permission.CAMERA
            } else {
                REQUIRED_PERMISSIONS
            }
            ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE_PERMISSIONS)
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            Log.d("Receiver", "USB Event: ${intent.action} for device: ${device?.deviceName}")
            
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        val isAccessory = (device.vendorId == 0x18D1 && (device.productId == 0x2D00 || device.productId == 0x2D01 || device.productId == 0x2D04 || device.productId == 0x2D05))
                        if (isAccessory) {
                            startReading(device)
                        } else {
                            Thread {
                                val success = usbManager.initiateHandshake(device)
                                runOnUiThread {
                                    if (success) statusText.text = "Status: Admin Mode - Switching Device..."
                                    else statusText.text = "Status: Handshake Failed"
                                }
                            }.start()
                        }
                    } else {
                        statusText.text = "Status: USB Permission Denied"
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (channel == "usb") performDiscovery()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    streamingThread?.interrupt()
                    statusText.text = "Status: USB Disconnected"
                }
            }
        }
    }

    private fun setupSettings() {
        confSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val threshold = progress / 100f
                yoloDetector?.confidenceThreshold = threshold
                confLabel.text = "AI Confidence: $progress%"
                Log.d("Receiver", "AI Threshold changed to: $threshold")
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun startLogic() {
        if (channel == "local") {
            startLocalCamera()
        } else if (channel == "usb") {
            startUsbReceiver()
        } else {
            startWifiReceiver()
        }
    }

    private fun startUsbReceiver() {
        videoTextureView.visibility = android.view.View.GONE
        statusText.text = "Status: USB Mode - Waiting for Broadcaster"
        performDiscovery()
        
        // Start aggressive periodic scan
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (channel != "usb") return
                if (streamingThread?.isAlive == true) return // Stop if already streaming
                
                Log.d("Receiver", "Periodic USB scan...")
                performDiscovery()
                handler.postDelayed(this, 2000) // 2s interval
            }
        }, 2000)
    }

    private fun performDiscovery() {
        if (channel != "usb") return
        if (streamingThread?.isAlive == true) return

        Thread {
            val manager = getSystemService(Context.USB_SERVICE) as UsbManager
            val devices = manager.deviceList
            
            if (devices.isEmpty()) {
                Log.d("Receiver", "No USB devices found")
                return@Thread
            }

            devices.values.forEach { device ->
                val vid = Integer.toHexString(device.vendorId)
                val pid = Integer.toHexString(device.productId)
                val isAccessory = (device.vendorId == 0x18D1 && (device.productId == 0x2D00 || device.productId == 0x2D01 || device.productId == 0x2D04 || device.productId == 0x2D05))
                
                Log.d("Receiver", "Checking device: $vid:$pid, isAccessory: $isAccessory")

                if (isAccessory) {
                    startReading(device)
                } else {
                    if (manager.hasPermission(device)) {
                        Log.d("Receiver", "Standard mode device $vid:$pid, initiating handshake...")
                        val success = usbManager.initiateHandshake(device)
                        runOnUiThread {
                            if (success) statusText.text = "Status: Switching Device to Camera..."
                        }
                    } else {
                        Log.d("Receiver", "Standard mode device $vid:$pid, requesting permission...")
                        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
                        manager.requestPermission(device, pi)
                        runOnUiThread { statusText.text = "Status: Requesting USB Permission..." }
                    }
                }
            }
        }.start()
    }

    private fun startReading(device: UsbDevice) {
        if (streamingThread?.isAlive == true) return
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (!manager.hasPermission(device)) {
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
            manager.requestPermission(device, pi)
            return
        }

        Thread {
            val connection = manager.openDevice(device) ?: return@Thread
            val usbInterface = device.getInterface(0)
            connection.claimInterface(usbInterface, true)

            var endpointIn: android.hardware.usb.UsbEndpoint? = null
            var endpointOut: android.hardware.usb.UsbEndpoint? = null
            for (i in 0 until usbInterface.endpointCount) {
                val ep = usbInterface.getEndpoint(i)
                if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) endpointIn = ep
                else if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_OUT) endpointOut = ep
            }

            if (endpointIn == null) {
                connection.close()
                return@Thread
            }

            streamingThread = Thread {
                runOnUiThread { 
                    statusText.text = "Status: Handshaking (USB)..." 
                    videoTextureView.visibility = android.view.View.VISIBLE
                }
                val buffer = ByteArray(16384)
                val packetBuffer = ByteBuffer.allocate(2 * 1024 * 1024).order(ByteOrder.LITTLE_ENDIAN)
                while (!Thread.interrupted()) {
                    val bytesRead = connection.bulkTransfer(endpointIn, buffer, buffer.size, 1000)
                    if (bytesRead > 0) processBytes(buffer, bytesRead, packetBuffer, connection, endpointOut)
                    else if (bytesRead < 0) break
                }
                connection.close()
            }
            streamingThread?.start()
        }.start()
    }

    private fun processBytes(data: ByteArray, length: Int, packetBuffer: ByteBuffer, connection: android.hardware.usb.UsbDeviceConnection?, endpointOut: android.hardware.usb.UsbEndpoint?) {
        packetBuffer.put(data, 0, length)
        packetBuffer.flip()
        while (packetBuffer.remaining() >= 5) {
            packetBuffer.mark()
            val dataSize = packetBuffer.int
            val type = packetBuffer.get()
            if (dataSize >= 0 && dataSize < 1024 * 1024 && packetBuffer.remaining() >= dataSize) {
                val payload = ByteArray(dataSize)
                packetBuffer.get(payload)
                when (type) {
                    1.toByte() -> {
                        frameCount++
                        val now = System.currentTimeMillis()
                        if (now - lastFpsTime >= 1000) {
                            currentFps = frameCount
                            frameCount = 0
                            lastFpsTime = now
                            runOnUiThread { fpsText.text = "FPS: $currentFps" }
                        }
                        decoder?.decode(payload)
                    }
                    2.toByte() -> {
                        runOnUiThread { 
                            statusText.text = "Status: Admin Phone Paired"
                            Toast.makeText(this, "Camera Linked!", Toast.LENGTH_SHORT).show() 
                        }
                        val confirmHeader = ByteBuffer.allocate(5).apply {
                            order(ByteOrder.LITTLE_ENDIAN)
                            putInt(0)
                            put(3.toByte())
                        }
                        connection?.bulkTransfer(endpointOut, confirmHeader.array(), 5, 1000)
                    }
                    4.toByte() -> handleResolutionInfo(payload)
                }
            } else if (dataSize < 0 || dataSize >= 1024 * 1024) {
                packetBuffer.clear()
                return
            } else {
                packetBuffer.reset()
                break
            }
        }
        packetBuffer.compact()
    }

    private fun startLocalCamera() {
        videoTextureView.visibility = android.view.View.VISIBLE
        statusText.text = "Status: Local Mode - Displaying Device Camera"
        
        // Use Landscape buffer (1920x1080) and rotate 90 degrees for Portrait
        applyTransform(1920, 1080, 90)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder()
                .setTargetResolution(Size(1920, 1080))
                .build().also {
                it.setSurfaceProvider { request ->
                    val st = videoTextureView.surfaceTexture
                    if (st != null) {
                        val surface = Surface(st)
                        request.provideSurface(surface, ContextCompat.getMainExecutor(this@ReceiverActivity)) { }
                    }
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                resText.text = "Res: 1080x1920"
                aspectText.text = "Aspect: 0.56"
            } catch (e: Exception) {
                Log.e("Receiver", "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startWifiReceiver() {
        videoTextureView.visibility = android.view.View.GONE
        statusText.text = "Status: Wi-Fi Mode - Waiting for Broadcaster..."
        
        wifiController.startServer(8888, object : WifiController.OnDataReceivedListener {
            override fun onDataReceived(type: Byte, data: ByteArray) {
                when (type) {
                    1.toByte() -> { // Video
                        if (statusText.text.contains("Waiting")) {
                            runOnUiThread { statusText.text = "Status: Receiving Stream" }
                        }
                        frameCount++
                        val now = System.currentTimeMillis()
                        if (now - lastFpsTime >= 1000) {
                            currentFps = frameCount
                            frameCount = 0
                            lastFpsTime = now
                            runOnUiThread { fpsText.text = "FPS: $currentFps" }
                        }
                        decoder?.decode(data)
                    }
                    2.toByte() -> { // Handshake Init
                        Log.d("Receiver", "Handshake Init received via Wi-Fi, confirming...")
                        runOnUiThread { 
                            statusText.text = "Status: Receiving Stream"
                            videoTextureView.visibility = android.view.View.VISIBLE
                            Toast.makeText(this@ReceiverActivity, "Pairing Confirmed!", Toast.LENGTH_SHORT).show()
                        }
                        wifiController.sendData(3.toByte(), ByteArray(0))
                    }
                    4.toByte() -> { // Resolution Info
                        handleResolutionInfo(data)
                    }
                }
            }
        })
    }

    private fun handleResolutionInfo(payload: ByteArray) {
        // Broadcaster sends "Width,Height,Rotation" string (Type 4)
        val metaString = String(payload)
        Log.d("Receiver", "Resolution Info string: $metaString")
        val parts = metaString.split(",")
        if (parts.size < 3) return

        val resW = parts[0].toIntOrNull() ?: 1920
        val resH = parts[1].toIntOrNull() ?: 1080
        val rotationDeg = parts[2].toIntOrNull() ?: 0
        applyTransform(resW, resH, rotationDeg)
    }

    private fun applyTransform(resW: Int, resH: Int, rotationDeg: Int) {
        Log.d("Receiver", "Applying transform: ${resW}x${resH}, rotation: $rotationDeg")
        runOnUiThread {
            val st = videoTextureView.surfaceTexture ?: return@runOnUiThread
            val viewWidth = videoTextureView.width.toFloat()
            val viewHeight = videoTextureView.height.toFloat()
            if (viewWidth == 0f || viewHeight == 0f) return@runOnUiThread

            st.setDefaultBufferSize(resW, resH)

            val matrix = Matrix()
            val centerX = viewWidth / 2f
            val centerY = viewHeight / 2f
            
            val isRotated = rotationDeg % 180 != 0
            val contentWidth = if (isRotated) resH.toFloat() else resW.toFloat()
            val contentHeight = if (isRotated) resW.toFloat() else resH.toFloat()

            // Calculate scale to fill the view (CenterCrop - Natural Camera Look)
            val scaleX = viewWidth / contentWidth
            val scaleY = viewHeight / contentHeight
            val finalScale = Math.max(scaleX, scaleY)

            // 1. Reset: TextureView by default fits the buffer to the view.
            // We scale it back to its original buffer dimensions relative to view center.
            matrix.setScale(resW / viewWidth, resH / viewHeight, centerX, centerY)
            
            // 2. Rotate
            if (rotationDeg != 0) {
                matrix.postRotate(rotationDeg.toFloat(), centerX, centerY)
            }
            
            matrix.postScale(finalScale, finalScale, centerX, centerY)
            
            videoTextureView.setTransform(matrix)
            overlayView.setTransform(matrix) // Keep AI overlay in sync with video transformation

            val contentAspect = if (contentHeight != 0f) contentWidth / contentHeight else 1.78f
            val aspectStr = String.format("%.2f", contentAspect)
            resText.text = "Res: ${resW}x${resH}"
            aspectText.text = "Aspect: $aspectStr (${if(isRotated) "P" else "L"})"

            if (channel != "local") {
                decoder?.stop()
                decoder = H264Decoder(Surface(videoTextureView.surfaceTexture!!), resW, resH)
                decoder?.start()
            }
        }
    }

    private fun restartReceiver() {
        decoder?.stop()
        decoder = null
        wifiController.close()
        startLogic()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, rg: IntArray) {
        super.onRequestPermissionsResult(rc, p, rg)
        if (rc == REQUEST_CODE_PERMISSIONS && allPermissionsGranted()) startLogic()
    }

    private fun initAi() {
        modelManager?.getModelFile()?.let { file ->
            yoloDetector = YoloDetector(this, file)
            startAiLoop()
        } ?: run {
            Log.d("Receiver", "Model not found, checking for update...")
            modelManager?.checkForUpdate { success ->
                if (success) {
                    modelManager?.getModelFile()?.let { file ->
                        yoloDetector = YoloDetector(this, file)
                        startAiLoop()
                    }
                }
            }
        }
    }

    private fun startAiLoop() {
        aiExecutor.execute {
            Log.d("Receiver", "AI Loop started")
            while (!Thread.interrupted()) {
                if (isAiEnabled && yoloDetector != null) {
                    try {
                        val startTime = System.currentTimeMillis()
                        val bitmap = runOnUiThreadWithResult {
                            if (videoTextureView.isAvailable) {
                                // Capture EXACTLY what is on screen (already rotated/cropped)
                                videoTextureView.getBitmap()
                            } else null
                        }
                        
                        bitmap?.let { 
                            if (frameCount % 30 == 0) {
                                Log.d("Receiver", "AI raw capture: ${it.width}x${it.height}")
                            }
                            // Detect will need to rotate this 1920x1080 -> 1080x1920
                            val results = yoloDetector?.detect(it) ?: emptyList()
                            val maxScore = yoloDetector?.getMaxScore() ?: 0f
                            val threshold = yoloDetector?.confidenceThreshold ?: 0f
                            val elapsed = System.currentTimeMillis() - startTime
                            
                            if (results.isNotEmpty() || frameCount % 30 == 0) {
                                Log.d("Receiver", "AI detected ${results.size} objects in ${elapsed}ms (Conf: $threshold, Max: $maxScore)")
                            }
                            runOnUiThread {
                                overlayView.setResults(results)
                            }
                        } ?: run {
                            // Log.d("Receiver", "TextureView bitmap not available")
                        }
                    } catch (e: Exception) {
                        Log.e("Receiver", "AI processing error: ${e.message}")
                    }
                }
                Thread.sleep(100)
            }
        }
    }

    private fun <T> runOnUiThreadWithResult(action: () -> T): T? {
        var result: T? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        runOnUiThread {
            result = action()
            latch.countDown()
        }
        latch.await(500, TimeUnit.MILLISECONDS)
        return result
    }

    private fun allPermissionsGranted() = (REQUIRED_PERMISSIONS + if (channel == "local") arrayOf(Manifest.permission.CAMERA) else emptyArray()).all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        Log.d("Receiver", "Surface Texture available: ${width}x${height}")
        if (channel == "local") {
            applyTransform(1080, 1920, 0)
        }
    }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        decoder?.stop()
        decoder = null
        return true
    }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (e: Exception) {}
        streamingThread?.interrupt()
        aiExecutor.shutdownNow()
        yoloDetector?.close()
        decoder?.stop()
        usbManager.close()
        wifiController.close()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 11
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
}
