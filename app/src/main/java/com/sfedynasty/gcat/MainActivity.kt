package com.sfedynasty.gcat

import android.nfc.NfcAdapter
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private val http = OkHttpClient()
    private val JSON = "application/json".toMediaType()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tvStatus = findViewById<TextView>(R.id.tv_status)
        val tvLog = findViewById<TextView>(R.id.tv_log)

        val nfc = NfcAdapter.getDefaultAdapter(this)
        if (nfc != null && nfc.isEnabled) {
            tvStatus.text = "Ready — hold phone to terminal"
        } else {
            tvStatus.text = "Enable NFC in Settings"
        }

        // Check live jPOS health on startup
        Thread {
            try {
                val resp = http.newCall(Request.Builder().url("https://gcat-jpos-host.onrender.com/health").build()).execute()
                val result = resp.body?.string() ?: ""
                runOnUiThread {
                    if (result.contains("healthy")) {
                        tvLog.text = "jPOS: healthy · v2.1.9"
                    } else {
                        tvLog.text = "jPOS: checking..."
                    }
                }
            } catch (e: IOException) {
                runOnUiThread { tvLog.text = "jPOS: connecting..." }
            }
        }.start()
    }
}
