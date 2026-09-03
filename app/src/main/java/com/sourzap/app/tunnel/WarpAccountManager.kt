package com.sourzap.app.tunnel

import android.content.Context
import android.util.Log
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class WarpAccountConfig(
    val privateKey: String,
    val publicKey: String,
    val assignedIpv4: String,
    val assignedIpv6: String?,
    val peerPublicKey: String,
    val endpoint: String = "162.159.192.1:53"
)

object WarpAccountManager {

    private const val TAG = "WarpAccountManager"
    private const val PREFS_NAME = "warp_account_prefs"
    private const val REG_URL = "https://api.cloudflareclient.com/v0a2158/reg"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    suspend fun getOrCreateConfig(context: Context): WarpAccountConfig = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedPrivKey = prefs.getString("private_key", null)
        val cachedPubKey = prefs.getString("public_key", null)
        val cachedIpv4 = prefs.getString("assigned_ipv4", null)
        val cachedPeerKey = prefs.getString("peer_public_key", null)
        val cachedEndpoint = prefs.getString("endpoint", "162.159.192.1:53") ?: "162.159.192.1:53"

        if (!cachedPrivKey.isNullOrBlank() &&
            !cachedPubKey.isNullOrBlank() &&
            !cachedIpv4.isNullOrBlank() &&
            !cachedPeerKey.isNullOrBlank()
        ) {
            return@withContext WarpAccountConfig(
                privateKey = cachedPrivKey,
                publicKey = cachedPubKey,
                assignedIpv4 = cachedIpv4,
                assignedIpv6 = prefs.getString("assigned_ipv6", null),
                peerPublicKey = cachedPeerKey,
                endpoint = cachedEndpoint
            )
        }

        // Generate a new WireGuard KeyPair
        val keyPair = KeyPair()
        val privKeyBase64 = keyPair.privateKey.toBase64()
        val pubKeyBase64 = keyPair.publicKey.toBase64()

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val tosTimestamp = dateFormat.format(Date())

        val requestJson = JSONObject().apply {
            put("key", pubKeyBase64)
            put("install_id", "")
            put("fcm_token", "")
            put("tos", tosTimestamp)
            put("model", "Android")
            put("serial_number", "")
            put("locale", "en_US")
        }

        val requestBody = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(REG_URL)
            .post(requestBody)
            .header("User-Agent", "okhttp/3.12.1")
            .header("CF-Client-Version", "a-6.10-2158")
            .header("Content-Type", "application/json")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string().orEmpty()
            throw IllegalStateException("Failed to register Cloudflare WARP account: HTTP ${response.code} $errorBody")
        }

        val responseBody = response.body?.string().orEmpty()
        val respJson = JSONObject(responseBody)
        val configObj = respJson.getJSONObject("config")
        val interfaceObj = configObj.getJSONObject("interface")
        val addressesObj = interfaceObj.getJSONObject("addresses")

        val rawIpv4 = addressesObj.getString("v4")
        val assignedIpv4 = if (rawIpv4.contains("/")) rawIpv4 else "$rawIpv4/32"
        val rawIpv6 = if (addressesObj.has("v6") && !addressesObj.isNull("v6")) addressesObj.getString("v6") else null
        val assignedIpv6 = if (!rawIpv6.isNullOrBlank()) {
            if (rawIpv6.contains("/")) rawIpv6 else "$rawIpv6/128"
        } else null

        val peersArray = configObj.getJSONArray("peers")
        val peerObj = peersArray.getJSONObject(0)
        val peerPublicKey = peerObj.getString("public_key")

        // Default to port 53 to completely bypass ISP port blocking
        val endpoint = "162.159.192.1:53"

        prefs.edit()
            .putString("private_key", privKeyBase64)
            .putString("public_key", pubKeyBase64)
            .putString("assigned_ipv4", assignedIpv4)
            .putString("assigned_ipv6", assignedIpv6)
            .putString("peer_public_key", peerPublicKey)
            .putString("endpoint", endpoint)
            .apply()

        Log.i(TAG, "Successfully provisioned Cloudflare WARP WireGuard account on port 53. Assigned IPv4: $assignedIpv4")

        WarpAccountConfig(
            privateKey = privKeyBase64,
            publicKey = pubKeyBase64,
            assignedIpv4 = assignedIpv4,
            assignedIpv6 = assignedIpv6,
            peerPublicKey = peerPublicKey,
            endpoint = endpoint
        )
    }
}
