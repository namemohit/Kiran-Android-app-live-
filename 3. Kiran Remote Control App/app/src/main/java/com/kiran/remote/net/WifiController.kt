package com.kiran.remote.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WifiController(private val context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    
    private var serviceType = "_broadcastcam._tcp."
    private var serviceName = "BroadcastCamera"
    
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var outputStream: OutputStream? = null

    fun setServiceConfig(name: String, type: String) {
        this.serviceName = name
        this.serviceType = type
        Log.d("Wifi", "Service config updated: Name=$name, Type=$type")
    }

    interface OnDataReceivedListener {
        fun onDataReceived(type: Byte, data: ByteArray)
    }

    fun startServer(port: Int, listener: OnDataReceivedListener) {
        close() // Ensure previous resources are freed
        Thread {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                registerService(serverSocket!!.localPort)
                Log.d("Wifi", "Server started on port ${serverSocket!!.localPort}")

                while (!Thread.interrupted()) {
                    Log.d("Wifi", "Waiting for client connection...")
                    val socket = serverSocket?.accept() ?: break
                    Log.i("Wifi", "Client connected from: ${socket.inetAddress}")
                    handleClient(socket, listener)
                }
            } catch (e: IOException) {
                Log.e("Wifi", "Server error", e)
            }
        }.start()
    }

    private fun handleClient(socket: Socket, listener: OnDataReceivedListener) {
        try {
            outputStream = socket.getOutputStream()
        } catch (e: Exception) {
            Log.e("Wifi", "Failed to get output stream", e)
        }

        Thread {
            try {
                val inputStream = socket.getInputStream()
                val headerBuffer = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
                val smallBuffer = ByteArray(5)

                while (!Thread.interrupted()) {
                    var read = 0
                    while (read < 5) {
                        val r = inputStream.read(smallBuffer, read, 5 - read)
                        if (r == -1) return@Thread
                        read += r
                    }
                    headerBuffer.clear()
                    headerBuffer.put(smallBuffer)
                    headerBuffer.flip()

                    val size = headerBuffer.int
                    val type = headerBuffer.get()

                    val payload = ByteArray(size)
                    var payloadRead = 0
                    while (payloadRead < size) {
                        val r = inputStream.read(payload, payloadRead, size - payloadRead)
                        if (r == -1) return@Thread
                        payloadRead += r
                    }
                    
                    listener.onDataReceived(type, payload)
                }
            } catch (e: IOException) {
                Log.e("Wifi", "Client handler error", e)
            } finally {
                socket.close()
            }
        }.start()
    }

    private fun registerService(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = this@WifiController.serviceName
            serviceType = this@WifiController.serviceType
            setPort(port)
        }
        Log.d("Wifi", "Registering NSD service: ${serviceInfo.serviceName} on port $port")
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    fun sendData(type: Byte, data: ByteArray) {
        val stream = outputStream ?: return
        Thread {
            try {
                val header = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
                header.putInt(data.size)
                header.put(type)
                synchronized(stream) {
                    stream.write(header.array())
                    stream.write(data)
                    stream.flush()
                }
            } catch (e: IOException) {
                Log.e("Wifi", "Send error", e)
            }
        }.start()
    }

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
            Log.i("Wifi", "Service registered successfully: ${NsdServiceInfo.serviceName}")
        }
        override fun onRegistrationFailed(arg0: NsdServiceInfo, arg1: Int) {
            Log.e("Wifi", "Service registration failed: $arg1")
        }
        override fun onServiceUnregistered(arg0: NsdServiceInfo) {
            Log.d("Wifi", "Service unregistered")
        }
        override fun onUnregistrationFailed(arg0: NsdServiceInfo, arg1: Int) {
            Log.e("Wifi", "Service unregistration failed: $arg1")
        }
    }

    fun close() {
        try {
            serverSocket?.close()
            clientSocket?.close()
            nsdManager.unregisterService(registrationListener)
        } catch (e: Exception) {}
    }
}
