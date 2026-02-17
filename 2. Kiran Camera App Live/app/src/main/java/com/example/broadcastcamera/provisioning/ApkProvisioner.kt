package com.example.broadcastcamera.provisioning

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * Extracts the current app's APK and serves it via a tiny HTTP server.
 */
class ApkProvisioner(private val context: Context) {
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private val port = 8080

    fun startServer(): String? {
        if (serverThread?.isAlive == true) return getLocalIpAddress()

        val apkPath = context.applicationInfo.publicSourceDir
        val apkFile = File(apkPath)
        
        if (!apkFile.exists()) {
            Log.e("ApkProvisioner", "APK not found at $apkPath")
            return null
        }

        serverThread = Thread {
            try {
                serverSocket = ServerSocket(port)
                Log.d("ApkProvisioner", "Serving APK from $apkPath on port $port")
                
                while (!Thread.interrupted()) {
                    val socket = serverSocket?.accept() ?: break
                    Thread {
                        try {
                            val input = socket.getInputStream()
                            // Read request (minimal HTTP/1.1 parsing)
                            val requestBuffer = ByteArray(1024)
                            input.read(requestBuffer)
                            
                            val output = socket.getOutputStream()
                            val responseHeader = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/vnd.android.package-archive\r\n" +
                                    "Content-Length: ${apkFile.length()}\r\n" +
                                    "Content-Disposition: attachment; filename=\"BroadcastCamera.apk\"\r\n" +
                                    "Connection: close\r\n\r\n"
                            
                            output.write(responseHeader.toByteArray())
                            
                            val apkInput = FileInputStream(apkFile)
                            val buffer = ByteArray(65536)
                            var read: Int
                            while (apkInput.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                            }
                            apkInput.close()
                            output.flush()
                            socket.close()
                        } catch (e: Exception) {
                            Log.e("ApkProvisioner", "Error serving request", e)
                        }
                    }.start()
                }
            } catch (e: Exception) {
                Log.e("ApkProvisioner", "Server error", e)
            }
        }.apply { start() }

        return getLocalIpAddress()
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress
                        val isIPv4 = sAddr.indexOf(':') < 0
                        if (isIPv4) return "http://$sAddr:$port/BroadcastCamera.apk"
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e("ApkProvisioner", "Error getting IP", ex)
        }
        return null
    }

    fun stopServer() {
        serverThread?.interrupt()
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverSocket = null
        serverThread = null
    }
}
