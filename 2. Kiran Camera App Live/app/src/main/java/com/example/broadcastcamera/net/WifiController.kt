package com.example.broadcastcamera.net

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

/**
 * Handles Wi-Fi streaming using TCP Sockets and NSD for discovery.
 */
class WifiController(private val context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    
    // Default values for standard camera broadcasting
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

    // --- Receiver (Server) Logic ---

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
                    val socket = serverSocket?.accept() ?: break
                    handleClient(socket, listener)
                }
            } catch (e: IOException) {
                Log.e("Wifi", "Server error", e)
            }
        }.start()
    }

    private fun handleClient(socket: Socket, listener: OnDataReceivedListener) {
        // For the Receiver (Server), we need to capture the client socket to send replies back.
        // In a more complex scenario, we'd manage a list of clients, but for this 1:1 pairing,
        // we can just set the active output stream.
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
                    
                    // Specific Handling for Type 5 (Touch Events) for global listener
                    if (type == 5.toByte()) {
                        Log.d("Wifi", "Touch event received via handleClient")
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

    // --- Broadcaster (Client) Logic ---

    fun discoverAndConnect(onConnected: () -> Unit) {
        Log.d("Wifi", "Starting discovery for type: $serviceType")
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener(onConnected))
    }

    fun connectToIp(ip: String, port: Int, onConnected: () -> Unit) {
        Thread {
            try {
                val socket = Socket(ip, port)
                clientSocket = socket
                outputStream = socket.getOutputStream()
                Log.d("Wifi", "Directly connected to $ip:$port")
                onConnected()
                
                // Always start listening for incoming data (Handshake responses)
                handleIncomingPackets(socket)
            } catch (e: Exception) {
                Log.e("Wifi", "Direct connection failed", e)
            }
        }.start()
    }

    fun sendData(type: Byte, data: ByteArray) {
        val stream = outputStream
        if (stream == null) {
            Log.w("Wifi", "Cannot send data: outputStream is null (Not connected)")
            return
        }
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
                Log.e("Wifi", "Send error (Type $type, Size ${data.size})", e)
            }
        }.start()
    }

    private fun discoveryListener(onConnected: () -> Unit) = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            Log.d("Wifi", "NSD Discovery started: $regType")
        }
        override fun onServiceFound(service: NsdServiceInfo) {
            Log.d("Wifi", "NSD Service found: ${service.serviceName}")
            if (service.serviceName.contains(this@WifiController.serviceName)) {
                Log.d("Wifi", "Matching service found, resolving...")
                nsdManager.resolveService(service, resolveListener(onConnected))
            }
        }
        override fun onServiceLost(service: NsdServiceInfo) {
            Log.d("Wifi", "NSD Service lost: ${service.serviceName}")
        }
        override fun onDiscoveryStopped(regType: String) {
            Log.d("Wifi", "NSD Discovery stopped")
        }
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e("Wifi", "NSD Start discovery failed: $errorCode")
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e("Wifi", "NSD Stop discovery failed: $errorCode")
        }
    }

    private var clientListener: OnDataReceivedListener? = null

    fun setClientListener(listener: OnDataReceivedListener) {
        this.clientListener = listener
    }

    private fun resolveListener(onConnected: () -> Unit) = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e("Wifi", "Resolve failed: $errorCode for ${serviceInfo.serviceName}")
        }
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            Log.d("Wifi", "Service resolved! IP: ${serviceInfo.host}, Port: ${serviceInfo.port}")
            Thread {
                try {
                    Log.d("Wifi", "Attempting socket connection to ${serviceInfo.host}:${serviceInfo.port}")
                    val socket = Socket(serviceInfo.host, serviceInfo.port)
                    clientSocket = socket
                    outputStream = socket.getOutputStream()
                    Log.i("Wifi", "Socket connected successfully!")
                    onConnected()
                    
                    // Always start listening for incoming data (Handshake responses)
                    handleIncomingPackets(socket)
                    
                } catch (e: Exception) {
                    Log.e("Wifi", "Socket connection failed", e)
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
                    
                    // Use current listener if set
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
