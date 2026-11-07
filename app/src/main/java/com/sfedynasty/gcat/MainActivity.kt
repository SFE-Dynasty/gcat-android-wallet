package com.sfedynasty.gcat

import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.nfc.NfcAdapter
import android.os.Bundle
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private val http = OkHttpClient()
    private val JSON = "application/json".toMediaType()
    private val JPOS = "https://gcat-jpos-host.onrender.com"
    private val PAN = "9000002500000001"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tvStatus = findViewById<TextView>(R.id.tv_status)
        val tvLog = findViewById<TextView>(R.id.tv_log)
        val tvNfc = findViewById<TextView>(R.id.tv_nfc)
        val btnTap = findViewById<TextView>(R.id.btn_tap)

        // NFC status
        val nfc = NfcAdapter.getDefaultAdapter(this)
        if (nfc != null && nfc.isEnabled) {
            tvStatus.text = "Ready — hold phone to terminal"
            startNfcPulse(tvNfc)
        } else {
            tvStatus.text = "Enable NFC in Settings"
        }

        // jPOS health check
        Thread {
            try {
                val resp = http.newCall(Request.Builder().url("$JPOS/health").build()).execute()
                val result = resp.body?.string() ?: ""
                runOnUiThread {
                    tvLog.text = if (result.contains("healthy")) "jPOS: healthy · v2.1.9" else "jPOS: checking..."
                }
            } catch (e: IOException) {
                runOnUiThread { tvLog.text = "jPOS: connecting..." }
            }
        }.start()

        // TAP TO PAY button
        btnTap.setOnClickListener {
            tvStatus.text = "Authorizing..."
            tvLog.text = "Sending to jPOS..."
            Thread {
                try {
                    val stan = (Math.random() * 999999).toInt().toString().padStart(6, '0')
                    val body = """{"field2_pan":"$PAN","field4_amount":1000,"field11_stan":"$stan","field49_currency":"840","uetr_parent":"762adade-c02f-441a-a12b-e940c1c022a6"}""".toRequestBody(JSON)
                    val resp = http.newCall(Request.Builder().url("$JPOS/authorize").post(body).build()).execute()
                    val result = resp.body?.string() ?: ""
                    runOnUiThread {
                        if (result.contains("\"00\"")) {
                            tvStatus.text = "✓ APPROVED"
                            tvLog.text = "Field 39: 00 · STAN: $stan"
                        } else {
                            tvStatus.text = "✗ DECLINED"
                            tvLog.text = result.take(80)
                        }
                    }
                } catch (e: IOException) {
                    runOnUiThread {
                        tvStatus.text = "Connection error"
                        tvLog.text = e.message ?: "unknown error"
                    }
                }
            }.start()
        }
    }

    private fun startNfcPulse(view: TextView) {
        val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.3f, 1f)
        val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.3f, 1f)
        val alpha = ObjectAnimator.ofFloat(view, "alpha", 1f, 0.4f, 1f)
        val set = AnimatorSet()
        set.playTogether(scaleX, scaleY, alpha)
        set.duration = 1500
        set.interpolator = AccelerateDecelerateInterpolator()
        set.repeatCount = ObjectAnimator.INFINITE
        set.start()
    }
}
