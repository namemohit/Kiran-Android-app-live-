package com.example.broadcastcamera.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Decodes H.264 data into a Surface.
 */
class H264Decoder(private val surface: Surface, private val width: Int, private val height: Int) {
    private var codec: MediaCodec? = null
    private var frameCount = 0

    fun start() {
        Log.d("H264Decoder", "Starting decoder $width x $height")
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        // Some devices need these for low latency or better compatibility
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        
        try {
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec?.configure(format, surface, null, 0)
            codec?.start()
            Log.d("H264Decoder", "Decoder started successfully")
        } catch (e: Exception) {
            Log.e("H264Decoder", "Failed to start decoder", e)
        }
    }

    fun decode(data: ByteArray) {
        val currentCodec = codec ?: return
        try {
            val inputBufferIndex = currentCodec.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = currentCodec.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()
                inputBuffer?.put(data)
                currentCodec.queueInputBuffer(inputBufferIndex, 0, data.size, System.nanoTime() / 1000, 0)
            } else if (inputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // Log.v("H264Decoder", "Input buffer not available, dropping frame")
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outputBufferIndex = currentCodec.dequeueOutputBuffer(bufferInfo, 10000)
            
            while (outputBufferIndex >= 0) {
                frameCount++
                if (frameCount % 30 == 0) {
                    Log.d("H264Decoder", "Decoded $frameCount frames")
                }
                currentCodec.releaseOutputBuffer(outputBufferIndex, true)
                outputBufferIndex = currentCodec.dequeueOutputBuffer(bufferInfo, 0)
            }
            
            if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.d("H264Decoder", "Output format changed: ${currentCodec.outputFormat}")
            }
        } catch (e: Exception) {
            Log.e("H264Decoder", "Error decoding", e)
        }
    }

    fun stop() {
        codec?.stop()
        codec?.release()
        codec = null
    }
}
