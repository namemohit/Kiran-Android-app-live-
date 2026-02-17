package com.kiran.remote

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kiran.remote.net.WifiController
import com.kiran.remote.video.H264Decoder
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ReceiverActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {
    private lateinit var wifiController: WifiController
    private var decoder: H264Decoder? = null
    
    private lateinit var videoTextureView: TextureView
    private lateinit var touchOverlay: View
    private lateinit var statusText: TextView
    private lateinit var resText: TextView
    private lateinit var fpsText: TextView
    private lateinit var ipText: TextView

    private var frameCount = 0
    private var lastFpsTime = 0L
    private var currentFps = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receiver)

        videoTextureView = findViewById(R.id.videoTextureView)
        touchOverlay = findViewById(R.id.touchOverlay)
        statusText = findViewById(R.id.statusText)
        resText = findViewById(R.id.resText)
        fpsText = findViewById(R.id.fpsText)
        ipText = findViewById(R.id.ipText)

        wifiController = WifiController(this)
        wifiController.setServiceConfig("KiranRemote", "_broadcastcam._tcp.")
        
        videoTextureView.surfaceTextureListener = this

        setupTouchListener()
        
        ipText.text = "IP: ${getLocalIpAddress()}"

        if (allPermissionsGranted()) {
            startLogic()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun setupTouchListener() {
        touchOverlay.setOnTouchListener { _, event ->
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
                // Normalize coordinates (0.0 to 1.0)
                val normX = event.x / touchOverlay.width.toFloat()
                val normY = event.y / touchOverlay.height.toFloat()

                sendTouchEvent(action, normX, normY)
            }
            true
        }
    }

    private fun sendTouchEvent(action: Int, x: Float, y: Float) {
        val buffer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(action)
        buffer.putFloat(x)
        buffer.putFloat(y)
        
        // Type 5: Remote Control Touch Event
        wifiController.sendData(5.toByte(), buffer.array())
    }

    private fun startLogic() {
        statusText.text = "Status: Remote - Waiting for Host..."
        
        wifiController.startServer(8888, object : WifiController.OnDataReceivedListener {
            override fun onDataReceived(type: Byte, data: ByteArray) {
                when (type) {
                    1.toByte() -> { // Video Stream
                        if (statusText.text.contains("Waiting")) {
                            runOnUiThread { statusText.text = "Status: Mirroring Active" }
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
                        Log.i("Remote", "Handshake Init (Type 2) received! Sending ACK (Type 3)")
                        runOnUiThread { 
                            statusText.text = "Status: Mirroring Active"
                            Toast.makeText(this@ReceiverActivity, "Phone Linked!", Toast.LENGTH_SHORT).show()
                        }
                        wifiController.sendData(3.toByte(), ByteArray(0))
                    }
                    4.toByte() -> { // Screen Resolution Info
                        handleResolutionInfo(data)
                    }
                }
            }
        })
    }

    private fun handleResolutionInfo(payload: ByteArray) {
        val metaString = String(payload)
        val parts = metaString.split(",")
        if (parts.size < 3) return

        val resW = parts[0].toIntOrNull() ?: 1080
        val resH = parts[1].toIntOrNull() ?: 1920
        val rotationDeg = parts[2].toIntOrNull() ?: 0

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

            // Normalize and Scale to fit Center Crop
            matrix.setScale(resW.toFloat() / viewWidth, resH.toFloat() / viewHeight, centerX, centerY)
            matrix.postRotate(rotationDeg.toFloat(), centerX, centerY)
            val scale = Math.max(viewWidth / contentWidth, viewHeight / contentHeight)
            matrix.postScale(scale, scale, centerX, centerY)
            
            videoTextureView.setTransform(matrix)

            resText.text = "Res: ${resW}x${resH}"

            decoder?.stop()
            decoder = H264Decoder(Surface(st), resW, resH)
            decoder?.start()
        }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, rg: IntArray) {
        super.onRequestPermissionsResult(rc, p, rg)
        if (rc == REQUEST_CODE_PERMISSIONS && allPermissionsGranted()) startLogic()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {}
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        decoder?.stop()
        decoder = null
        return true
    }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Remote", "Error getting IP", e)
        }
        return "0.0.0.0"
    }

    override fun onDestroy() {
        super.onDestroy()
        decoder?.stop()
        wifiController.close()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 12
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
}
