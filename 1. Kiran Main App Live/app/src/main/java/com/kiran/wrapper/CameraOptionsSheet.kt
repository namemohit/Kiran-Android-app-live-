package com.kiran.wrapper

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class CameraOptionsSheet : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.layout_camera_options, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Button>(R.id.btnLocal).setOnClickListener {
            handleSelection("Local")
        }
        view.findViewById<Button>(R.id.btnWifi).setOnClickListener {
            handleSelection("Wifi")
        }
        view.findViewById<Button>(R.id.btnUsb).setOnClickListener {
            handleSelection("USB")
        }
    }

    private fun handleSelection(option: String) {
        if (option == "Wifi" || option == "Local" || option == "USB") {
            val intent = android.content.Intent(requireContext(), ReceiverActivity::class.java).apply {
                putExtra("channel", option.lowercase())
            }
            startActivity(intent)
        } else {
            Toast.makeText(context, "Selected: $option (Implementation pending)", Toast.LENGTH_SHORT).show()
        }
        dismiss()
    }

    companion object {
        const val TAG = "CameraOptionsSheet"
    }
}
