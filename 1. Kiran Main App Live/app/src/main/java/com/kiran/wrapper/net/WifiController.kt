package com.kiran.wrapper.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles Wi-Fi streaming using TCP Sockets and NSD for discovery.
 */
class WifiController(private val context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val SERVICE_TYPE = "_broadcastcam._tcp."
    private val SERVICE_NAME = "BroadcastCamera"

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var outputStream: OutputStream? = null

    interface OnDataReceivedListener {
        fun onDataReceived(type: Byte, data: ByteArray)
    }

    // --- Receiver (Server) Logic ---

    fun startServer(port: Int, listener: OnDataReceivedListener) {
        Thread {
            try {
                serverSocket = ServerSocket(port)
                registerService(serverSocket!!.localPort)
                Log.d("Wifi", "Server started on port ${serverSocket!!.localPort}")

                while (!Thread.interrupted()) {
                    val socket = serverSocket?.accept() ?: break
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
                    // Read header
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
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    // --- Broadcaster (Client) Logic ---

    fun discoverAndConnect(onConnected: () -> Unit) {
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener(onConnected))
    }

    fun connectToIp(ip: String, port: Int, onConnected: () -> Unit) {
        Thread {
            try {
                val socket = Socket(ip, port)
                clientSocket = socket
                outputStream = socket.getOutputStream()
                Log.d("Wifi", "Directly connected to $ip:$port")
                onConnected()
                
                handleIncomingPackets(socket)
            } catch (e: Exception) {
                Log.e("Wifi", "Direct connection failed", e)
            }
        }.start()
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

    private fun discoveryListener(onConnected: () -> Unit) = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {}
        override fun onServiceFound(service: NsdServiceInfo) {
            if (service.serviceName.contains(SERVICE_NAME)) {
                nsdManager.resolveService(service, resolveListener(onConnected))
            }
        }
        override fun onServiceLost(service: NsdServiceInfo) {}
        override fun onDiscoveryStopped(regType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
    }

    private var clientListener: OnDataReceivedListener? = null

    fun setClientListener(listener: OnDataReceivedListener) {
        this.clientListener = listener
    }

    private fun resolveListener(onConnected: () -> Unit) = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            Thread {
                try {
                    val socket = Socket(serviceInfo.host, serviceInfo.port)
                    clientSocket = socket
                    outputStream = socket.getOutputStream()
                    Log.d("Wifi", "Connected to ${serviceInfo.host}:${serviceInfo.port}")
                    onConnected()
                    
                    handleIncomingPackets(socket)
                    
                } catch (e: Exception) {
                    Log.e("Wifi", "Connection failed", e)
                }
            }.start()
        }
    }

    private fun handleIncomingPackets(socket: Socket) {
        Thread {
            try {
                val inputStream = socket.getInputStream()
                val headerBuffer = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
                val smallBuffer = ByteArray(5)

                while (!Thread.interrupted() && !socket.isClosed) {
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
                    
                    clientListener?.onDataReceived(type, payload)
                }
            } catch (e: Exception) {
                if (!socket.isClosed) {
                    Log.e("Wifi", "Incoming packet handler error", e)
                }
            }
        }.start()
    }

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {}
        override fun onRegistrationFailed(arg0: NsdServiceInfo, arg1: Int) {}
        override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
        override fun onUnregistrationFailed(arg0: NsdServiceInfo, arg1: Int) {}
    }

    fun close() {
        try {
            serverSocket?.close()
            clientSocket?.close()
            nsdManager.unregisterService(registrationListener)
        } catch (e: Exception) {}
    }
}
