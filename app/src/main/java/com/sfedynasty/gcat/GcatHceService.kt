package com.sfedynasty.gcat

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class GcatHceService : HostApduService() {

    companion object {
        private const val TAG = "GcatHCE"
        private const val JPOS = "https://gcat-jpos-host.onrender.com"
        private const val PAN = "4815200000250000"
        private val JSON = "application/json".toMediaType()
    }

    private val http = OkHttpClient()

    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray {
        val hex = apdu.toHex()
        Log.d(TAG, "APDU: $hex")
        return when {
            hex.startsWith("00A4") -> { fireApdu("SELECT"); fromHex("6F108408A000000999534645A5009000") }
            hex.startsWith("80A8") -> { fireApdu("GET;PROCESSING_OPTIONS"); fromHex("800A56005800800101009000") }
            hex.startsWith("00B2") -> { fireApdu("READ_RECORD"); fromHex("70145A084815200000250000" + "5F030228129000") }
            hex.startsWith("80AE") -> { fireAuthorize(); fromHex("80129F2608" + System.currentTimeMillis().toString(16).padStart(16, '0').toUpperCase() + "9F360200019000") }
            else -> fromHex("6F00")
        }
    }

    private fun fireApdu(cmd: String) {
        Thread {
            try {
                val body = """{\"apdu_command\":\"$cmd\",\"pan\":\"$PAN\"}""".toRequestBody(JSON)
                http.newCall(Request.Builder().url("$JPOS/hce/apdu").post(body).build()).execute()
            } catch (e: IOException) { Log.e(TAG, e.message ?: "error") }
        }.start()
    }

    private fun fireAuthorize() {
        Thread {
            try {
                val stan = (Math.random() * 999999).toInt().toString().padStart(6, '0')
                val body = """{\"field2_pan\":\"$PAN\",\"field4_amount\":1000,\"field11_stan\":\"$stan\",\"field49_currency\":\"840\",\"uetr_parent\":\"762adade-c02f-441a-a12b-e940c1c022a6\"}""".toRequestBody(JSON)
                val resp = http.newCall(Request.Builder().url("$JPOS/authorize").post(body).build()).execute()
                val result = resp.body?.string() ?: ""
                Log.d(TAG, "Auth: $result")
                if (result.contains("\"00\"")) Log.d(TAG, "APPROVED - Field 39: 00")
                else Log.w(TAG, "DECLINED: $result")
            } catch (e: IOException) { Log.e(TAG, e.message ?: "error") }
        }.start()
    }

    override fun onDeactivated(reason: Int) { Log.d(TAG, "Deactivated: $reason") }

    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }
    private fun fromHex(hex: String) = ByteArray(hex.length / 2) { i ->
        ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hey[i * 2 + 1], 16)).toByte()
    }
}
