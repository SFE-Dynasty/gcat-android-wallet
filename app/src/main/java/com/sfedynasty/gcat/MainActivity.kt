package com.sfedynasty.gcat

import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.graphics.Bitmap
import android.nfc.NfcAdapter
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
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
        val btnScan = findViewById<TextView>(R.id.btn_scan) // NEW
        val ivQrCode = findViewById<ImageView>(R.id.iv_qr_code)

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

        // --- BUTTON 1: TAP TO PAY (Simulates NFC Authorization) ---
        btnTap.setOnClickListener {
            // Hide QR if it's open
            ivQrCode.visibility = View.GONE
            
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

        // --- BUTTON 2: SCAN TO PAY (Generates QR Code) ---
        btnScan.setOnClickListener {
            if (ivQrCode.visibility == View.VISIBLE) {
                // If already visible, hide it and reset
                ivQrCode.visibility = View.GONE
                btnScan.text = "⬛  SCAN TO PAY"
                tvStatus.text = "Ready — hold phone to terminal"
                tvLog.text = "jPOS: healthy · v2.1.9"
                return@setOnClickListener
            }

            tvStatus.text = "Generating QR..."
            tvLog.text = "Requesting payload from jPOS..."
            
            Thread {
                try {
                    // 1. Ping the live server
                    val body = """{"amount": "10.00", "currency": "USD"}""".toRequestBody(JSON)
                    http.newCall(Request.Builder().url("$JPOS/qr/generate").post(body).build()).execute().close()

                    // 2. Build EMVCo TLV string
                    val emvCoPayload = "000201" + 
                                       "010212" + 
                                       "26300014com.sfedynasty" + "0108" + PAN + 
                                       "52045999" + 
                                       "5303840" +  
                                       "540510.00" + 
                                       "5802US" +   
                                       "5917SF EMERALD DYNASTY" + 
                                       "6007NEW YORK" + 
                                       "6304"       

                    runOnUiThread {
                        try {
                            // 3. Generate the QR
                            val barcodeEncoder = BarcodeEncoder()
                            val bitmap: Bitmap = barcodeEncoder.encodeBitmap(emvCoPayload, BarcodeFormat.QR_CODE, 600, 600)
                            
                            // 4. Show it on screen
                            ivQrCode.setImageBitmap(bitmap)
                            ivQrCode.visibility = View.VISIBLE
                            
                            tvStatus.text = "Scan to Pay"
                            tvLog.text = "EMVCo CPM Active · $10.00"
                            btnScan.text = "HIDE QR CODE"
                        } catch (e: Exception) {
                            tvStatus.text = "QR Error"
                            tvLog.text = e.message ?: "Failed to generate QR"
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
        val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.3f, 1f).apply {
            duration = 1500
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
        }
        val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.3f, 1f).apply {
            duration = 1500
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
        }
        val alpha = ObjectAnimator.ofFloat(view, "alpha", 1f, 0.4f, 1f).apply {
            duration = 1500
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
        }
        val set = AnimatorSet()
        set.playTogether(scaleX, scaleY, alpha)
        set.start()
    }
}
