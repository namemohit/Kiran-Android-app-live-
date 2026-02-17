package com.example.broadcastcamera.usb

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles the communication over USB using AOA (Android Open Accessory).
 * The Broadcaster acts as the Accessory.
 */
class BroadcasterUsbManager(context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var outputStream: FileOutputStream? = null
    private var inputStream: FileInputStream? = null

    fun openAccessory(accessory: UsbAccessory): Boolean {
        if (fileDescriptor != null) {
            Log.d("BroadcasterUSB", "Accessory already open, ignoring request")
            return true
        }
        
        try {
            fileDescriptor = usbManager.openAccessory(accessory)
            if (fileDescriptor != null) {
                val fd = fileDescriptor!!.fileDescriptor
                outputStream = FileOutputStream(fd)
                inputStream = FileInputStream(fd)
                Log.d("BroadcasterUSB", "Successfully opened accessory $accessory")
                return true
            } else {
                Log.e("BroadcasterUSB", "openAccessory returned null for $accessory. Check system permissions or if another app is using it.")
            }
        } catch (e: Exception) {
            Log.e("BroadcasterUSB", "Exception opening accessory", e)
        }
        return false
    }

    fun sendData(type: Byte, data: ByteArray) {
        val stream = outputStream ?: return
        try {
            val header = ByteBuffer.allocate(5).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                putInt(data.size)
                put(type)
            }
            synchronized(stream) {
                stream.write(header.array())
                stream.write(data)
                stream.flush()
            }
        } catch (e: IOException) {
            Log.e("USB", "Error sending data", e)
        }
    }

    private var readingThread: Thread? = null

    fun startReading(listener: (Byte, ByteArray) -> Unit) {
        val stream = inputStream ?: return
        if (readingThread?.isAlive == true) return // Already reading
        
        readingThread = Thread {
            try {
                val headerBuffer = ByteBuffer.allocate(5).apply { order(ByteOrder.LITTLE_ENDIAN) }
                val headerBytes = ByteArray(5)
                
                while (!Thread.interrupted()) {
                    var read = 0
                    while (read < 5) {
                        val r = stream.read(headerBytes, read, 5 - read)
                        if (r == -1) return@Thread
                        read += r
                    }
                    headerBuffer.clear()
                    headerBuffer.put(headerBytes)
                    headerBuffer.flip()
                    
                    val size = headerBuffer.int
                    val type = headerBuffer.get()
                    
                    if (size < 0 || size > 5 * 1024 * 1024) {
                        Log.e("USB", "Invalid size $size")
                        return@Thread
                    }

                    val payload = ByteArray(size)
                    var payloadRead = 0
                    while (payloadRead < size) {
                        val r = stream.read(payload, payloadRead, size - payloadRead)
                        if (r == -1) return@Thread
                        payloadRead += r
                    }
                    listener(type, payload)
                }
            } catch (e: IOException) {
                Log.e("USB", "Error reading data", e)
            }
        }.apply { start() }
    }

    fun close() {
        try {
            fileDescriptor?.close()
        } catch (e: IOException) {}
        fileDescriptor = null
        outputStream = null
        inputStream = null
    }
}
