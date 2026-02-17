package com.example.broadcastcamera.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Encodes video frames from a Surface into H.264.
 */
class H264Encoder(private val width: Int, private val height: Int, private val bitrate: Int) {
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    interface OnDataListener {
        fun onDataEncoded(data: ByteArray)
    }

    private var listener: OnDataListener? = null

    fun setListener(l: OnDataListener) {
        this.listener = l
    }

    fun start(): Boolean {
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second between I-frames
            format.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec?.createInputSurface()
            codec?.start()

            // Start a thread to pull encoded data
            Thread {
                while (codec != null) {
                    drainOutput()
                }
            }.start()
            true
        } catch (e: Exception) {
            Log.e("H264Encoder", "Failed to start encoder", e)
            false
        }
    }

    private var frameCount = 0

    private fun drainOutput() {
        val currentCodec = codec ?: return
        try {
            val outputBufferIndex = currentCodec.dequeueOutputBuffer(bufferInfo, 10000)
            if (outputBufferIndex >= 0) {
                val outputBuffer = currentCodec.getOutputBuffer(outputBufferIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    
                    val data = ByteArray(bufferInfo.size)
                    outputBuffer.get(data)
                    
                    frameCount++
                    if (frameCount % 60 == 0) {
                        Log.d("H264Encoder", "Encoded frame $frameCount, size: ${data.size}")
                    }
                    
                    listener?.onDataEncoded(data)
                }
                currentCodec.releaseOutputBuffer(outputBufferIndex, false)
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.d("H264Encoder", "Output format changed: ${currentCodec.outputFormat}")
            }
        } catch (e: Exception) {
            Log.e("H264Encoder", "Error draining encoder", e)
        }
    }

    fun getInputSurface(): Surface? = inputSurface

    fun stop() {
        codec?.stop()
        codec?.release()
        codec = null
        inputSurface = null
    }
}
