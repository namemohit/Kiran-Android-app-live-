package com.example.broadcastcamera.ui

import android.Manifest
import android.content.pm.PackageManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.broadcastcamera.databinding.ActivityReceiverBinding
import com.example.broadcastcamera.usb.ReceiverUsbManager
import com.example.broadcastcamera.net.WifiController
import com.example.broadcastcamera.video.H264Decoder
import com.example.broadcastcamera.provisioning.ApkProvisioner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.InputStream

class ReceiverActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {
    private lateinit var binding: ActivityReceiverBinding
    private lateinit var usbManager: ReceiverUsbManager
    private lateinit var wifiController: WifiController
    private var decoder: H264Decoder? = null
    private var streamingThread: Thread? = null
    private var channel: String = "usb"
    private var receivedPacketCount = 0
    private var lastFpsTime = 0L
    private var frameCount = 0
    private var currentFps = 0
    private var lastResolution = "0x0"
    private var lastAspectRatio = "0:0"
    private lateinit var apkProvisioner: ApkProvisioner
    private var provisioningUri: String? = null

    private val ACTION_USB_PERMISSION = "com.example.broadcastcamera.USB_PERMISSION"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReceiverBinding.inflate(layoutInflater)
        setContentView(binding.root)

        channel = intent.getStringExtra("channel") ?: "usb"
        usbManager = ReceiverUsbManager(this)
        wifiController = WifiController(this)
        apkProvisioner = ApkProvisioner(this)
        binding.videoTextureView.surfaceTextureListener = this

        if (allPermissionsGranted()) {
            startLogic()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
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
                    Log.d("Receiver", "USB Permission result: $granted for device: ${device?.deviceName}")
                    if (granted && device != null) {
                        val isAccessory = (device.vendorId == 0x18D1 && (device.productId == 0x2D00 || device.productId == 0x2D01 || device.productId == 0x2D04 || device.productId == 0x2D05))
                        if (isAccessory) {
                            Log.d("Receiver", "Accessory permission granted, starting read...")
                            startReading(device)
                        } else {
                            Log.d("Receiver", "Standard mode permission granted, initiating handshake...")
                            Thread {
                                val success = usbManager.initiateHandshake(device, provisioningUri)
                                runOnUiThread {
                                    if (success) binding.statusText.text = "Status: Admin Mode - Switching Device..."
                                    else binding.statusText.text = "Status: Handshake Failed"
                                }
                            }.start()
                        }
                    } else {
                        binding.statusText.text = "Status: USB Permission Denied"
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    startLogic() // Re-scan devices
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    streamingThread?.interrupt()
                    binding.statusText.text = "Status: USB Disconnected"
                }
            }
        }
    }

    private fun startLogic() {
        // Hide videoTextureView until paired
        binding.videoTextureView.visibility = android.view.View.GONE
        
        binding.retryButton.setOnClickListener {
            Log.d("Receiver", "Manual Retry clicked")
            performDiscovery()
        }

        binding.helpButton.setOnClickListener {
            showUsbHelpDialog()
        }

        binding.provisionButton.setOnClickListener {
            if (provisioningUri == null) {
                provisioningUri = apkProvisioner.startServer()
                if (provisioningUri != null) {
                    binding.provisionStatusText.visibility = android.view.View.VISIBLE
                    binding.provisionStatusText.text = "Provision Link ACTIVE: $provisioningUri"
                    Toast.makeText(this, "Admin Phone is now ready to Provision New Phones!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Failed to start Provisioning Server", Toast.LENGTH_SHORT).show()
                }
            } else {
                apkProvisioner.stopServer()
                provisioningUri = null
                binding.provisionStatusText.visibility = android.view.View.GONE
                Toast.makeText(this, "Provisioning Server Stopped", Toast.LENGTH_SHORT).show()
            }
        }

        if (channel == "usb") {
            binding.statusText.text = "Status: USB Mode - Waiting for Broadcaster"
            performDiscovery()
            // Start periodic scan if no devices found
            startPeriodicScan()
        } else {
            binding.statusText.text = "Status: Wi-Fi Mode - Waiting for Broadcaster..."
            wifiController.startServer(8888, object : WifiController.OnDataReceivedListener {
                override fun onDataReceived(type: Byte, data: ByteArray) {
                    when (type) {
                        1.toByte() -> { // Video
                            if (binding.statusText.text.contains("Waiting")) {
                                runOnUiThread { binding.statusText.text = "Status: Receiving Stream" }
                            }
                            frameCount++
                            val now = System.currentTimeMillis()
                            if (now - lastFpsTime >= 1000) {
                                currentFps = frameCount
                                frameCount = 0
                                lastFpsTime = now
                                runOnUiThread { binding.fpsText.text = "FPS: $currentFps" }
                            }
                            decoder?.decode(data)
                        }
                        2.toByte() -> { // Handshake Init
                            Log.d("Receiver", "Handshake Init received via Wi-Fi, confirming...")
                            runOnUiThread { 
                                binding.statusText.text = "Status: Receiving Stream"
                                binding.videoTextureView.visibility = android.view.View.VISIBLE
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
    }

    private fun showUsbHelpDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("USB Connection Guide")
            .setMessage("For USB streaming to work:\n\n" +
                        "1. PLACEMENT: The OTG Adapter MUST be on this phone (the Receiver).\n" +
                        "2. CABLE: Connect a standard USB cable from the OTG adapter to the Broadcaster phone.\n" +
                        "3. SYSTEM: Ensure 'OTG Connection' is enabled in Android Settings on BOTH phones.\n" +
                        "4. POWER: Ensure the Broadcaster phone shows it is charging.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun startPeriodicScan() {
        // ... handled in performDiscovery ...
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                val manager = getSystemService(Context.USB_SERVICE) as UsbManager
                if (manager.deviceList.isEmpty()) {
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
            val devices = manager.deviceList
            val accessories = manager.accessoryList
            
            // Low-level detection logging
            Log.d("Receiver", "Low-level check: ${devices.size} USB devices, ${accessories?.size ?: 0} accessories found")
            if (devices.isNotEmpty()) {
                devices.values.forEach { 
                    Log.d("Receiver", "Detected Device: ${it.deviceName} VID:${it.vendorId} PID:${it.productId}")
                }
            }
            
            devices.values.forEach { device ->
                // ... AOA handshake logic ...
                val vid = Integer.toHexString(device.vendorId)
                val pid = Integer.toHexString(device.productId)
                Log.d("Receiver", "Found Device: ${device.deviceName} VID: $vid PID: $pid")
                
                val isAccessory = (device.vendorId == 0x18D1 && (device.productId == 0x2D00 || device.productId == 0x2D01 || device.productId == 0x2D04 || device.productId == 0x2D05))
                
                if (isAccessory) {
                    Log.d("Receiver", "Device $vid:$pid IS in Accessory mode. Starting read...")
                    startReading(device)
                } else {
                    Log.d("Receiver", "Device $vid:$pid is in Standard mode. Checking permission...")
                    if (manager.hasPermission(device)) {
                        Log.d("Receiver", "Permission already granted for $vid:$pid. Initiating handshake...")
                        val success = usbManager.initiateHandshake(device, provisioningUri)
                        Log.d("Receiver", "Handshake result: $success")
                        runOnUiThread {
                            if (success) {
                                binding.statusText.text = "Status: Switching Device to Camera..."
                            }
                        }
                    } else {
                        Log.d("Receiver", "Requesting USB permission for $vid:$pid...")
                        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
                        manager.requestPermission(device, pi)
                        runOnUiThread {
                            binding.statusText.text = "Status: Requesting USB Permission..."
                        }
                    }
                }
            }
        }.start()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, rg: IntArray) {
        super.onRequestPermissionsResult(rc, p, rg)
        if (rc == REQUEST_CODE_PERMISSIONS && allPermissionsGranted()) startLogic()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 11
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }


    private fun startReading(device: UsbDevice) {
        if (streamingThread?.isAlive == true) {
            Log.d("Receiver", "Reading thread already active, ignoring...")
            return
        }
        
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        
        // Final permission check for the accessory-mode identity
        if (!manager.hasPermission(device)) {
            Log.d("Receiver", "Missing permission for Accessory mode device! Requesting...")
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
            manager.requestPermission(device, pi)
            return
        }

        Thread {
            val connection = manager.openDevice(device) ?: run {
                Log.e("Receiver", "Failed to open device connection even with permission")
                return@Thread
            }
            val usbInterface = device.getInterface(0)
            connection.claimInterface(usbInterface, true)

            // Find the IN/OUT endpoints
            var endpointIn: android.hardware.usb.UsbEndpoint? = null
            var endpointOut: android.hardware.usb.UsbEndpoint? = null
            for (i in 0 until usbInterface.endpointCount) {
                val ep = usbInterface.getEndpoint(i)
                if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) {
                    endpointIn = ep
                } else if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_OUT) {
                    endpointOut = ep
                }
            }

            if (endpointIn == null) {
                Log.e("Receiver", "No IN endpoint found")
                connection.close()
                return@Thread
            }

            streamingThread = Thread {
            runOnUiThread { 
                binding.statusText.text = "Status: Handshaking (USB)..." 
                binding.statusText.setTextColor(android.graphics.Color.WHITE)
            }
            Log.d("Receiver", "Starting USB stream read thread")
            
            val buffer = ByteArray(16384) // 16KB read buffer
            val packetBuffer = ByteBuffer.allocate(2 * 1024 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            
            while (!Thread.interrupted()) {
                val bytesRead = connection.bulkTransfer(endpointIn, buffer, buffer.size, 1000)
                if (bytesRead > 0) {
                    processBytes(buffer, bytesRead, packetBuffer, connection, endpointOut)
                } else if (bytesRead < 0) {
                    Log.e("Receiver", "Bulk transfer error: $bytesRead")
                    break
                }
            }
            connection.close()
        }
            streamingThread?.start()
        }.start()
    }

    private fun handleResolutionInfo(payload: ByteArray) {
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val resW = bb.int
        val resH = bb.int
        val rotationDeg = if (payload.size >= 12) bb.int else 0
        Log.d("Receiver", "Resolution Info received: ${resW}x${resH}, rotation: $rotationDeg")
        runOnUiThread {
            val st = binding.videoTextureView.surfaceTexture ?: return@runOnUiThread
            val viewWidth = binding.videoTextureView.width.toFloat()
            val viewHeight = binding.videoTextureView.height.toFloat()
            if (viewWidth == 0f || viewHeight == 0f) return@runOnUiThread

            // 1. Tell the TextureView what size the buffer is (Fixes tearing/stride)
            st.setDefaultBufferSize(resW, resH)

            val matrix = Matrix()
            val centerX = viewWidth / 2f
            val centerY = viewHeight / 2f
            
            val isRotated = rotationDeg % 180 != 0
            val contentWidth = if (isRotated) resH.toFloat() else resW.toFloat()
            val contentHeight = if (isRotated) resW.toFloat() else resH.toFloat()

            // 2. NORMALIZE: Undo the default stretch (Identity)
            matrix.setScale(resW.toFloat() / viewWidth, resH.toFloat() / viewHeight, centerX, centerY)

            // 3. ROTATE
            matrix.postRotate(rotationDeg.toFloat(), centerX, centerY)

            // 4. SCALE for CENTER_CROP
            val scale = Math.max(viewWidth / contentWidth, viewHeight / contentHeight)
            matrix.postScale(scale, scale, centerX, centerY)
            
            binding.videoTextureView.setTransform(matrix)

            // Update Diagnostics
            val contentAspect = if (contentHeight != 0f) contentWidth / contentHeight else 1.78f
            val aspectStr = String.format("%.2f", contentAspect)
            binding.resText.text = "Res: ${resW}x${resH}"
            binding.aspectText.text = "Aspect: $aspectStr (${if(isRotated) "P" else "L"})"

            decoder?.stop()
            if (st != null) {
                decoder = H264Decoder(Surface(st), resW, resH)
                decoder?.start()
            }
        }
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
                    1.toByte() -> { // Video
                        receivedPacketCount++
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
                        decoder?.decode(payload)
                    }
                    2.toByte() -> { // Handshake Init
                        Log.d("Receiver", "Handshake Init received, sending Confirm...")
                        runOnUiThread { 
                            binding.statusText.text = "Status: Admin Phone Paired"
                            binding.videoTextureView.visibility = android.view.View.VISIBLE
                            Toast.makeText(this, "Camera Linked!", Toast.LENGTH_SHORT).show() 
                        }
                        // Send Confirm (Type 3)
                        val confirmHeader = ByteBuffer.allocate(5).apply {
                            order(ByteOrder.LITTLE_ENDIAN)
                            putInt(0)
                            put(3.toByte())
                        }
                        if (channel == "usb" && connection != null && endpointOut != null) {
                            connection.bulkTransfer(endpointOut, confirmHeader.array(), 5, 1000)
                        } else if (channel == "wifi") {
                            wifiController.sendData(3.toByte(), ByteArray(0))
                        }
                    }
                    4.toByte() -> { // Resolution Info
                        handleResolutionInfo(payload)
                    }
                }
            } else if (dataSize < 0 || dataSize >= 1024 * 1024) {
                Log.e("Receiver", "Invalid data size: $dataSize, clearing buffer")
                packetBuffer.clear()
                return
            } else {
                packetBuffer.reset()
                break
            }
        }
        packetBuffer.compact()
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        Log.d("Receiver", "Surface Texture available: ${width}x${height}")
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
        apkProvisioner.stopServer()
        usbManager.close()
        wifiController.close()
    }
}
