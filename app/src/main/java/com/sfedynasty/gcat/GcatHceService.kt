package com.sfedynasty.gcat

import android.content.Context
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class GcatHceService : HostApduService() {

    companion object {
        private const val TAG = "GcatHCE"
        private const val JPOS = "https://gcat-jpos-host.onrender.com"
        private const val PAN = "9000002500000001"
        private const val PIN = "5559"
        val AID = byteArrayOf(0xF0.toByte(), 0x00, 0x00, 0x00, 0x99.toByte(), 0x53, 0x46, 0x45)
        private val JSON = "application/json".toMediaType()

        private val SW_OK           = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_NOT_FOUND    = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val SW_WRONG_PIN    = byteArrayOf(0x63.toByte(), 0xC2.toByte())
        private val SW_PIN_BLOCKED  = byteArrayOf(0x69.toByte(), 0x83.toByte())
        private val SW_UNKNOWN      = byteArrayOf(0x6F.toByte(), 0x00)
    }

    private val http = OkHttpClient()
    private var pinVerified = false
    private var pinTries = 3

    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray {
        if (apdu.size < 4) return SW_UNKNOWN
        val ins = apdu[1].toInt() and 0xFF
        val p1  = apdu[2].toInt() and 0xFF
        val p2  = apdu[3].toInt() and 0xFF
        val hex = apdu.toHex()
        Log.d(TAG, "APDU IN: $hex")

        return when {
            ins == 0xA4 && p1 == 0x04 -> handleSelectAid(apdu)
            ins == 0xA8 -> handleGpo()
            ins == 0xB2 -> handleReadRecord(p1, p2)
            ins == 0x20 && p1 == 0x00 -> handleVerifyPin(apdu)
            ins == 0xAE -> handleGenerateAc()
            else -> {
                Log.w(TAG, "Unknown APDU: $hex")
                SW_UNKNOWN
            }
        }
    }

    private fun handleSelectAid(apdu: ByteArray): ByteArray {
        if (apdu.size < 5) return SW_NOT_FOUND
        val aidLen = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + aidLen) return SW_NOT_FOUND
        val receivedAid = apdu.copyOfRange(5, 5 + aidLen)

        if (!receivedAid.contentEquals(AID)) {
            Log.w(TAG, "AID mismatch")
            return SW_NOT_FOUND
        }

        Log.d(TAG, "SELECT AID matched")
        fireApdu("SELECT_AID")

        val fci = byteArrayOf(
            0x6F, 0x13,
            0x84.toByte(), 0x08,
            0xF0.toByte(), 0x00, 0x00, 0x00, 0x99.toByte(), 0x53, 0x46, 0x45,
            0xA5.toByte(), 0x07,
            0x9F.toByte(), 0x38, 0x01, 0x00,
            0x5F.toByte(), 0x2D, 0x02, 0x65, 0x6E
        )
        return fci + SW_OK
    }

    private fun handleGpo(): ByteArray {
        fireApdu("GET_PROCESSING_OPTIONS")
        val gpo = byteArrayOf(
            0x80.toByte(), 0x0A,
            0x56.toByte(), 0x00,
            0x58.toByte(), 0x00,
            0x80.toByte(), 0x01, 0x01, 0x00
        )
        return gpo + SW_OK
    }

    private fun handleReadRecord(record: Int, sfi: Int): ByteArray {
        fireApdu("READ_RECORD")
        val readRecord = byteArrayOf(
            0x70, 0x17,
            0x5A, 0x08,
            0x90.toByte(), 0x00, 0x00, 0x25, 0x00, 0x00, 0x00, 0x01,
            0x5F.toByte(), 0x24, 0x03,
            0x28, 0x12, 0x31.toByte(),
            0x5F.toByte(), 0x30, 0x02,
            0x01, 0x01
        )
        return readRecord + SW_OK
    }

    private fun handleVerifyPin(apdu: ByteArray): ByteArray {
        if (pinTries <= 0) return SW_PIN_BLOCKED
        if (apdu.size < 6) return SW_WRONG_PIN
        val pinBlock = apdu.copyOfRange(5, apdu.size)
        val pinLen = pinBlock[0].toInt() and 0x0F
        val enteredPin = StringBuilder()
        for (i in 0 until pinLen) {
            val byteIdx = 1 + i / 2
            if (byteIdx >= pinBlock.size) break
            val nibble = if (i % 2 == 0) (pinBlock[byteIdx].toInt() shr 4) and 0x0F
                         else pinBlock[byteIdx].toInt() and 0x0F
            enteredPin.append(nibble)
        }
        return if (enteredPin.toString() == PIN) {
            pinVerified = true
            pinTries = 3
            SW_OK
        } else {
            pinTries--
            byteArrayOf(0x63.toByte(), (0xC0 or pinTries).toByte())
        }
    }

    private fun handleGenerateAc(): ByteArray {
        Log.d(TAG, "GENERATE AC --- firing authorize")
        fireAuthorize()
        val ac = byteArrayOf(
            0x80.toByte(), 0x12,
            0x9F.toByte(), 0x27, 0x01, 0x40,
            0x9F.toByte(), 0x26, 0x08,
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x9F.toByte(), 0x36, 0x02, 0x00, 0x01
        )
        return ac + SW_OK
    }

    private fun fireApdu(cmd: String) {
        Thread {
            try {
                val body = """{"apdu_command":"$cmd","pan":"$PAN"}""".toRequestBody(JSON)
                http.newCall(Request.Builder().url("$JPOS/hce/apdu").post(body).build()).execute().close()
            } catch (e: IOException) { Log.e(TAG, "fireApdu error: ${e.message}") }
        }.start()
    }

    private fun fireAuthorize() {
        Thread {
            try {
                val stan = (Math.random() * 999999).toInt().toString().padStart(6, '0')
                
                // NO MORE HARDCODED $10.00! IT READS FROM THE BRIDGE!
                val amountToCharge = SharedData.currentAmountCents
                
                val body = """{"field2_pan":"$PAN","field4_amount":$amountToCharge,"field11_stan":"$stan","field49_currency":"840","uetr_parent":"762adade-c02f-441a-a12b-e940c1c022a6","pin_verified":$pinVerified}""".toRequestBody(JSON)
                val resp = http.newCall(Request.Builder().url("$JPOS/authorize").post(body).build()).execute()
                val result = resp.body?.string() ?: ""
                Log.d(TAG, "Auth response: $result")
                if (result.contains("\"00\"")) {
                    Log.d(TAG, "APPROVED --- Field 39: 00")
                    vibrate(longArrayOf(0, 100, 50, 100), false)
                } else {
                    Log.w(TAG, "DECLINED: $result")
                    vibrate(longArrayOf(0, 500), false)
                }
            } catch (e: IOException) { Log.e(TAG, "fireAuthorize error: ${e.message}") }
        }.start()
    }

    private fun vibrate(pattern: LongArray, repeat: Boolean) {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(pattern, if (repeat) 0 else -1)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, if (repeat) 0 else -1)
            }
        } catch (e: Exception) { Log.e(TAG, "Vibrate error: ${e.message}") }
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "Deactivated: $reason")
        pinVerified = false
    }

    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }
}
