package com.example.broadcastcamera.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RemoteInputService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("RemoteInput", "Accessibility Service Connected")
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private var activeStroke: GestureDescription.StrokeDescription? = null
    private var lastPath: Path? = null

    fun performTouch(action: Int, normX: Float, normY: Float) {
        val dm = resources.displayMetrics
        val x = normX * dm.widthPixels
        val y = normY * dm.heightPixels

        Log.d("RemoteInput", "Gesture event: action=$action, x=$x, y=$y")

        when (action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                val path = Path()
                path.moveTo(x, y)
                lastPath = path
                activeStroke = GestureDescription.StrokeDescription(path, 0, 100, true)
                dispatchGesture(GestureDescription.Builder().addStroke(activeStroke!!).build(), null, null)
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val path = lastPath ?: return
                path.lineTo(x, y)
                activeStroke = activeStroke?.continueStroke(path, 0, 100, true)
                activeStroke?.let {
                    dispatchGesture(GestureDescription.Builder().addStroke(it).build(), null, null)
                }
            }
            android.view.MotionEvent.ACTION_UP -> {
                val path = lastPath ?: return
                path.lineTo(x, y)
                activeStroke = activeStroke?.continueStroke(path, 0, 50, false)
                activeStroke?.let {
                    dispatchGesture(GestureDescription.Builder().addStroke(it).build(), null, null)
                }
                activeStroke = null
                lastPath = null
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    companion object {
        var instance: RemoteInputService? = null
    }
}
