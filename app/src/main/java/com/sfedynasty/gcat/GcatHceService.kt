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
        // Proprietary closed-loop AID --- starts with F per ISO 7816
    val AID = byteArrayOf(0xF0.toByte(), 0x00, 0x00, 0x00, 0x99.toByte(), 0x53, 0x46, 0x45)
        private val JSON = "application/json".toMediaType()

        // Status words
        private val SW_OK           = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_NOT_FOUND    = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val SW_WRONG_PIN    = byteArrayOf(0x63.toByte(), 0xC2.toByte()) // 2 tries remaining
        private val SW_PIN_BLOCKED  = byteArrayOf(0x69.toByte(), 0x83.toByte())
        private val SW_UNKNOWN      = byteArrayOf(0x6F.toByte(), 0x00)
    }

    private val http = OkHttpClient()
    private var pinVerified = false
    private var pinTries = 3

    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray {
        if (apdu.size < 4) return SW_UNKNOWN
        val cla = apdu[0].toInt() and 0xFF
        val ins = apdu[1].toInt() and 0xFF
        val p1  = apdu[2].toInt() and 0xFF
        val p2  = apdu[3].toInt() and 0xFF
        val hex = apdu.toHex()
        Log.d(TAG, "APDU IN: $hex")

        return when {
            // SELECT AID: 00 A4 4 00 [len] [AID bytes]
            ins == 0xA4 && p1 == 0x04 -> handleSelectAid(apdu)

            // GET PROCESSING OPTIONS: 80 A8 8 00
            ins == 0xA8 -> handleGpo()

            // READ RECORD: 00 B2 [record] [sfi]
            ins == 0xB2 -> handleReadRecord(p1, p2)

            // VERIFY PIN: 00 20 00 80 [len] [PIN block]
            ins == 0x20 && p1 == 0x00 -> handleVerifyPin(apdu)

            // GENERATE AC (final authorization): 80 AE {ref} 00
            ins == 0xAE -> handleGenerateAc()

            else -> {
                Log.w(TAG, "Unknown APDU: $hex")
                SW_UNKNOWN
            }
        }
    }

    private fun handleSelectAid(apdu: ByteArray): ByteArray {
        // Extract AI with APDU: byte 4 is length, bytes 5..5+len are AID
        if (apdu.size < 5) return SW_NOT_FOUND
        val aidLen = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + aidLen) return SW_NOT_FOUND
        val receivedAid = apdu.copyOfRange(5, 5 + aidLen)

        if (!receivedAid.contentEquals(AID)) {
            Log.w(TAG, "AID mismatch: ${receivedAid.toHex()} vs ${AID.toHex()}")
            return SW_NOT_FOUND
        }

        Log.d(TAG, "SELECT AID matched: ${AID.toHex()}")
        fireApdu("SELECT_AID")

        // FCI response: 6F [len] 84 [aidLen] [AID] A5 04 9F38 01 00 90 00
        // Keep well under 256 bytes
        val fci = byteArrayOf(
            0x6F, 0x13,                                       // FCI Template
            0x84.toByte(), 0x08,                              // DF Name tag + length
            0xF0.toByte(), 0x00, 0x00, 0x00, 0x99.toByte(), 0x53, 0x46, 0x45, // AID
            0xA5.toByte(), 0x07,                              // FCI Proprietary
            0x9F.toByte(), 0x38, 0x01, 0x00,                 // PDOL (empty)
            0x5F.toByte(), 0x2D, 0x02, 0x65, 0x6E            // Language: en
        )
        return fci + SW_OK
    }

    private fun handleGpo(): ByteArray {
        Log.d(TAG, "GET PR·OCESSING OPTIONS")
        fireApdu("GET;PROCESSING_OPTIONS")
        // AIP + AFL response
        val gpo = byteArrayOf(
            0x80.toByte(), 0x0A,
            0x56.toByte(), 0x00,       // AIP: CDA spported
            0x58.toByte(), 0x00,
            0x80.toByte(), 0x01, 0x01, 0x00  // AFL: SFI 1, record 1
        )
        return gpo + SW_OK
    }

    private fun handleReadRecord(record: Int, sfi: Int): ByteArray {
        Log.d(TAG, "READ RECORD $record SFI $sfi")
        fireApdu("READ_RECORD")
        // PAN: 9000002500000001, expiry 2812, service code 101
        val readRecord = byteArrayOf(
            0x70, 0x17,
            0x5A, 0x08,                                       // PAN tag
            0x90.toByte(), 0x00, 0x00, 0x25, 0x00, 0x00, 0x00, 0x01, // PAN bytes
            0x5F.toByte(), 0x24, 0x03,                       // Expiry tag
            0x28, 0x12, 0x31.toByte(),                       // 2028/12/31
            0x5F.toByte(), 0x30, 0x02,                       // Service code tag
            0x01, 0x01                                        // Service code 101
        )
        return readRecord + SW_OK
    }

    private fun handleVerifyPin(apdu: ByteArray): ByteArray {
        if (pinTries <= 0) {
            Log.w(TAG, "PIN blocked")
            return SW_PIN_BLOCKED
        }
        // Extract PIN from PIN block (plaintext PIN block format)
        // PIN block: byte 5 onwards, first nibble of byte 5 = 2 (plaintext), second nibble = PIN length
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
        Log.d(TAG, "PIN VERIFY attempt")
        return if (enteredPin.toString() == PIN) {
            pinVerified = true
            pinTries = 3
            Log.d(TAG, "PIN verified OK")
            SW_OK
        } else {
            pinTries--
            Log.w(TAG, "PIN wrong, tries left: $pinTries")
            byteArrayOf(0x63.toByte(), (0xC0 or pinTries).toByte())
        }
    }

    private fun handleGenerateAc(): ByteArray {
        Log.d(TAG, "GENERATE AC --- firing authorize")
        fireAuthorize()
        // Cryptogram response (TC = Transaction Certificate = approved offline)
        val ac = byteArrayOf(
            0x80.toByte(), 0x12,
            0x9F.toByte(), 0x27, 0x01, 0x40,  // CID: TC (approved)
            0x9F.toByte(), 0x26, 0x08,         // AC tag
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, // dummy AC
            0x9F.toByte(), 0x36, 0x02, 0x00, 0x01  // ATC
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
                val body = """{"field2_pan":"$PAN","field4_amount":1000,"field11_stan":"$stan","field49_currency":"840","uetr_parent":"762adade-c02f-441a-a12b-e940c1c022a6","pin_verified":$pinVerified}""".toRequestBody(JSON)
                val resp = http.newCall(Request.Builder().url("$JPOS/authorize").post(body).build()).execute()
                val result = resp.body?.string() ?: ""
                Log.d(TAG, "Auth response: $result")
                if (result.contains("\"00\"")) {
                    Log.d(TAG, "APPROVED --- Field 39: 00")
                    vibrate(longArrayOf(0, 100, 50, 100), false)  // double pulse = approved
                } else {
                    Log.w(TAG, "DECLINED: $result")
                    vibrate(longArrayOf(0, 500), false)  // long buzz = declined
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
        pinVerified = false  // reset PIN state on deactivation
    }

    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }
}
