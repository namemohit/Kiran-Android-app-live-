package com.kiran.wrapper.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles the communication as a USB Host.
 * The Receiver acts as the Host and initiates the AOA handshake.
 */
class ReceiverUsbManager(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null

    // Handshake constants for AOA
    private val ACCESSORY_GET_PROTOCOL = 51
    private val ACCESSORY_SEND_ID = 52
    private val ACCESSORY_START = 53

    fun initiateHandshake(device: UsbDevice, provisionUri: String? = null): Boolean {
        Log.d("ReceiverUSB", "Initiating AOA handshake for ${device.deviceName}")
        val connection = usbManager.openDevice(device) ?: run {
            Log.e("ReceiverUSB", "Failed to open device ${device.deviceName}")
            return false
        }
        this.connection = connection

        // 1. Get Protocol
        val protocol = ByteArray(2)
        val res = connection.controlTransfer(0xC0, ACCESSORY_GET_PROTOCOL, 0, 0, protocol, 2, 1000)
        if (res < 0) {
            Log.e("ReceiverUSB", "ACCESSORY_GET_PROTOCOL failed: $res")
            return false
        }
        val version = (protocol[1].toInt() shl 8) or (protocol[0].toInt() and 0xFF)
        Log.d("ReceiverUSB", "AOA Protocol version: $version")

        // 2. Send ID strings
        Log.d("ReceiverUSB", "Sending ID strings...")
        sendString(connection, ACCESSORY_SEND_ID, 0, "YantrAI") // Manufacturer
        sendString(connection, ACCESSORY_SEND_ID, 1, "BroadcastCamera") // Model
        sendString(connection, ACCESSORY_SEND_ID, 2, "Camera Receiver") // Description
        sendString(connection, ACCESSORY_SEND_ID, 3, "1.0") // Version
        sendString(connection, ACCESSORY_SEND_ID, 4, provisionUri ?: "https://github.com/YantrAI") // URI
        sendString(connection, ACCESSORY_SEND_ID, 5, "12345678") // Serial

        // 3. Start Accessory
        Log.d("ReceiverUSB", "Sending ACCESSORY_START...")
        val startRes = connection.controlTransfer(0x40, ACCESSORY_START, 0, 0, null, 0, 1000)
        Log.d("ReceiverUSB", "ACCESSORY_START result: $startRes")
        
        // CRITICAL: Close the connection immediately after starting accessory mode.
        // This allows the OS to re-enumerate the device as an accessory faster.
        connection.close()
        this.connection = null
        
        return startRes >= 0
    }

    private fun sendString(conn: UsbDeviceConnection, req: Int, index: Int, string: String) {
        val bytes = string.toByteArray()
        val res = conn.controlTransfer(0x40, req, 0, index, bytes, bytes.size, 1000)
        if (res < 0) {
            Log.e("ReceiverUSB", "Failed to send string index $index: $res")
        }
    }

    fun close() {
        connection?.close()
        connection = null
    }
}
