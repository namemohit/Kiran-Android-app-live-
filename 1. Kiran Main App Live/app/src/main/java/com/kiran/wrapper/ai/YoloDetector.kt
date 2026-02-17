package com.kiran.wrapper.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.kiran.wrapper.ui.DetectionResult
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class YoloDetector(private val context: Context, modelFile: File) {

    private var interpreter: Interpreter? = null
    private val inputSize = 256
    var confidenceThreshold = 0.1f // Updated dynamically by UI
    private var overallMaxScore = 0f
    private var frameCounter = 0

    init {
        try {
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                // Use GPU if available
                // addDelegate(GpuDelegate()) 
            }
            interpreter = Interpreter(modelFile, options)
            Log.d("YoloDetector", "Interpreter initialized successfully with ${modelFile.absolutePath}")
            
            // Log Input/Output Tensors
            val inputTensor = interpreter?.getInputTensor(0)
            val outputTensor = interpreter?.getOutputTensor(0)
            Log.d("YoloDetector", "Input Tensor: ${inputTensor?.shape()?.contentToString()}")
            Log.d("YoloDetector", "Output Tensor: ${outputTensor?.shape()?.contentToString()}")
        } catch (e: Exception) {
            Log.e("YoloDetector", "Failed to initialize TFLite: ${e.message}")
        }
    }

    /**
     * Runs inference on the provided bitmap.
     * @return List of detections with normalized coordinates (0.0 to 1.0)
     */
    fun detect(bitmap: Bitmap): List<DetectionResult> {
        val tInterpreter = interpreter ?: return emptyList()
        overallMaxScore = 0f

        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(0f, 255f))
            .build()

        var tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)
        
        // Debug: Check input bitmap validity
        if (frameCounter % 30 == 0) {
            val midPixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            Log.d("Receiver", "Input Bitmap: ${bitmap.width}x${bitmap.height}, MidPixel: ${Integer.toHexString(midPixel)}")
        }

        // 2. Prepare Output Buffer
        val outputTensor = tInterpreter.getOutputTensor(0)
        val outputShape = outputTensor.shape() // [1, 5, 1344] or [1, 1344, 5]
        
        // Handle potential variations in shape
        val (numElements, numAnchors, isTransposed) = if (outputShape[1] < outputShape[2]) {
             Triple(outputShape[1], outputShape[2], true) // [1, 5, 1344]
        } else {
             Triple(outputShape[2], outputShape[1], false) // [1, 1344, 5]
        }
        
        Log.d("YoloDetector", "Elements: $numElements, Anchors: $numAnchors, Transposed: $isTransposed")

        val outputBuffer = ByteBuffer.allocateDirect(1 * outputShape[1] * outputShape[2] * 4)
            .order(ByteOrder.nativeOrder())
        
        // 3. Run Inference
        tInterpreter.run(tensorImage.buffer, outputBuffer)
        outputBuffer.rewind()

        // 4. Post-process
        val detections = mutableListOf<DetectionResult>()
        val outputArray = FloatArray(outputShape[1] * outputShape[2])
        outputBuffer.asFloatBuffer().get(outputArray)

        // Debug: Log info about output to check if model is alive
        var arrayMax = -1000f
        var arrayMin = 1000f
        for (f in outputArray) {
            if (f > arrayMax) arrayMax = f
            if (f < arrayMin) arrayMin = f
        }
        
        frameCounter++
        // Log every 10 frames for now to be absolutely sure we see it
        if (frameCounter % 10 == 0) {
            val sample = outputArray.take(5).joinToString(", ")
            Log.d("Receiver", "AI raw range: [$arrayMin to $arrayMax], Sample: [$sample]")
        }

        // Detect if this is an OBB model (7 elements typical: x,y,w,h,cls0,cls1,angle)
        // Correct OBB indexing: scores start at 4, angle is at the last index (numElements-1)
        val isOBB = numElements >= 6 && numElements <= 10 
        val classStartIdx = 4
        val classEndIdx = if (isOBB) numElements - 1 else numElements
        
        if (frameCounter % 30 == 1) {
            Log.d("Receiver", "Model Format: ${if(isOBB) "OBB" else "Standard"}, Elements: $numElements, Anchors: $numAnchors, ClassIndices: $classStartIdx to ${classEndIdx-1}")
        }

        for (i in 0 until numAnchors) {
            var maxScore = 0f
            var classId = -1
            
            // Find max score among classes
            for (c in classStartIdx until classEndIdx) {
                val index = if (isTransposed) c * numAnchors + i else i * numElements + c
                if (index >= outputArray.size) continue
                
                val score = outputArray[index]
                if (score > maxScore) {
                    maxScore = score
                    classId = c - classStartIdx
                }
            }

            if (maxScore > overallMaxScore) overallMaxScore = maxScore

            if (maxScore > confidenceThreshold) {
                // Log.d("YoloDetector", "Detection found! Score: $maxScore, Class: $classId")
                val idx0 = if (isTransposed) 0 * numAnchors + i else i * numElements + 0
                val idx1 = if (isTransposed) 1 * numAnchors + i else i * numElements + 1
                val idx2 = if (isTransposed) 2 * numAnchors + i else i * numElements + 2
                val idx3 = if (isTransposed) 3 * numAnchors + i else i * numElements + 3

                val x = outputArray[idx0]
                val y = outputArray[idx1]
                val w = outputArray[idx2]
                val h = outputArray[idx3]
                
                /*
                if (detections.size < 3) {
                    Log.d("Receiver", "Raw Box $i: x=$x, y=$y, w=$w, h=$h")
                }
                */

                // Mapping coordinates to 0.0-1.0 range
                // Note: YOLOv8 outputs are often center-based in input_pixels
                // But some exports are normalized 0-1. 
                // We guess based on magnitude.
                val normX = if (x > 1.1f) x / inputSize else x
                val normY = if (y > 1.1f) y / inputSize else y
                val normW = if (w > 1.1f) w / inputSize else w
                val normH = if (h > 1.1f) h / inputSize else h

                val left = normX - normW / 2f
                val top = normY - normH / 2f
                val right = normX + normW / 2f
                val bottom = normY + normH / 2f

                detections.add(
                    DetectionResult(
                        box = RectF(
                            Math.max(0f, left), 
                            Math.max(0f, top), 
                            Math.min(1f, right), 
                            Math.min(1f, bottom)
                        ),
                        label = "Object $classId",
                        score = maxScore
                    )
                )
            }
        }
        
        if (detections.isEmpty()) {
            Log.d("Receiver", "No detections above threshold. Max score seen: $overallMaxScore")
        }
        
        return detections
    }

    fun getMaxScore(): Float = overallMaxScore

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
