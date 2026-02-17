package com.example.broadcastcamera.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.broadcastcamera.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBroadcaster.setOnClickListener {
            val intent = Intent(this, BroadcasterActivity::class.java)
            intent.putExtra("channel", getSelectedChannel())
            startActivity(intent)
        }

        binding.btnReceiver.setOnClickListener {
            val intent = Intent(this, ReceiverActivity::class.java)
            intent.putExtra("channel", getSelectedChannel())
            startActivity(intent)
        }

        binding.btnRemoteHost.setOnClickListener {
            startActivity(Intent(this, ScreenStreamingActivity::class.java))
        }
    }

    private fun getSelectedChannel(): String {
        return if (binding.rbUsb.isChecked) "usb" else "wifi"
    }
}
