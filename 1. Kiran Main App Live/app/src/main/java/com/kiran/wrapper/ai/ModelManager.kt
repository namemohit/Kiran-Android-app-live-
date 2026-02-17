package com.kiran.wrapper.ai

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.URL

class ModelManager(private val context: Context) {
    private val modelFileName = "best_yolov8n_obb_v0_256_float16.tflite"
    private val modelUrl = "https://your-server.com/models/$modelFileName" // Placeholder
    private val localModelFile = File(context.filesDir, modelFileName)

    fun getModelFile(): File? {
        // 1. Check if cached model exists in internal storage
        if (localModelFile.exists()) {
            return localModelFile
        }

        // 2. Try to copy from assets if it exists there (initial install fallback)
        try {
            context.assets.open(modelFileName).use { input ->
                FileOutputStream(localModelFile).use { output ->
                    input.copyTo(output)
                }
            }
            return localModelFile
        } catch (e: Exception) {
            Log.e("ModelManager", "Model not found in assets: ${e.message}")
        }

        return null
    }

    /**
     * Downloads the latest model from the server.
     * This can be called periodically or when a version change is detected.
     */
    fun checkForUpdate(onComplete: (Boolean) -> Unit) {
        Thread {
            try {
                // In a real app, you'd check a version/checksum first
                Log.d("ModelManager", "Starting model download from $modelUrl")
                URL(modelUrl).openStream().use { input ->
                    FileOutputStream(localModelFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d("ModelManager", "Model update successful")
                onComplete(true)
            } catch (e: Exception) {
                Log.e("ModelManager", "Model download failed: ${e.message}")
                onComplete(false)
            }
        }.start()
    }
}
