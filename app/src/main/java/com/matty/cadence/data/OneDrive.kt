package com.matty.cadence.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * The library lives in OneDrive › Apps › Cadence — the same folder Cadence Studio uploads to and the
 * Apple TV reads. Sign-in is Microsoft's device-code flow (same app registration as the TV): show a short
 * code, approve it at microsoft.com/devicelogin.
 */
object OneDriveConfig {
    const val CLIENT_ID = "bd062135-8d4a-444b-a904-c9ad7b31d144"
    const val SCOPE = "Files.ReadWrite.AppFolder offline_access"
    const val AUTH = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
    const val APPROOT = "https://graph.microsoft.com/v1.0/me/drive/special/approot"
}

class GraphException(message: String, val code: Int = 0) : Exception(message)

val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

class OneDriveAuth(context: Context) {
    data class DeviceCode(val userCode: String, val url: String, val deviceCode: String, val interval: Int, val expiresAt: Long)

    private val prefs = context.getSharedPreferences("onedrive", Context.MODE_PRIVATE)

    var pending by mutableStateOf<DeviceCode?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var refreshToken by mutableStateOf(prefs.getString("refresh", null))
        private set

    val signedIn: Boolean get() = refreshToken != null

    private var accessToken: String? = null
    private var expiresAt = 0L

    private fun saveRefresh(rt: String?) {
        refreshToken = rt
        prefs.edit().putString("refresh", rt).apply()
    }

    /** Starts device-code sign-in; call [poll] next (from a coroutine) to wait for approval. */
    suspend fun startSignIn(): DeviceCode? {
        error = null
        return try {
            val r = post("${OneDriveConfig.AUTH}/devicecode",
                mapOf("client_id" to OneDriveConfig.CLIENT_ID, "scope" to OneDriveConfig.SCOPE))
            val user = r.str("user_code")
            val device = r.str("device_code")
            if (user == null || device == null) {
                error = r.str("error_description") ?: "Couldn't start sign-in."
                null
            } else {
                val code = DeviceCode(
                    userCode = user,
                    url = r.str("verification_uri") ?: "https://microsoft.com/devicelogin",
                    deviceCode = device,
                    interval = r["interval"]?.jsonPrimitive?.intOrNull ?: 5,
                    expiresAt = System.currentTimeMillis() + 1000L * (r["expires_in"]?.jsonPrimitive?.intOrNull ?: 900),
                )
                pending = code
                code
            }
        } catch (e: Exception) {
            error = e.message ?: "Couldn't reach Microsoft."
            null
        }
    }

    /** Waits until the code is approved (true), refused or expired (false). */
    suspend fun poll(code: DeviceCode): Boolean {
        while (System.currentTimeMillis() < code.expiresAt) {
            delay(code.interval * 1000L)
            if (pending != code) return false              // cancelled / restarted
            val r = try {
                post("${OneDriveConfig.AUTH}/token", mapOf(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "client_id" to OneDriveConfig.CLIENT_ID, "device_code" to code.deviceCode))
            } catch (e: Exception) { continue }
            if (store(r)) {
                pending = null
                return true
            }
            val err = r.str("error")
            if (err != "authorization_pending" && err != "slow_down") {
                error = r.str("error_description") ?: "Sign-in failed."
                pending = null
                return false
            }
        }
        if (pending == code) {
            pending = null
            error = "The code expired — try again."
        }
        return false
    }

    fun cancelSignIn() {
        pending = null
    }

    fun signOut() {
        saveRefresh(null)
        accessToken = null
        expiresAt = 0
    }

    private fun store(r: JsonObject): Boolean {
        val at = r.str("access_token") ?: return false
        accessToken = at
        expiresAt = System.currentTimeMillis() + 1000L * ((r["expires_in"]?.jsonPrimitive?.intOrNull ?: 3600) - 120)
        r.str("refresh_token")?.let { saveRefresh(it) }
        return true
    }

    suspend fun token(): String {
        accessToken?.let { if (System.currentTimeMillis() < expiresAt) return it }
        val rt = refreshToken ?: throw GraphException("Sign in to OneDrive in Settings.", 401)
        val r = post("${OneDriveConfig.AUTH}/token", mapOf(
            "grant_type" to "refresh_token", "client_id" to OneDriveConfig.CLIENT_ID,
            "refresh_token" to rt, "scope" to OneDriveConfig.SCOPE))
        if (!store(r)) {
            if (r.str("error") == "invalid_grant") saveRefresh(null)
            throw GraphException("Sign in to OneDrive in Settings.", 401)
        }
        return accessToken!!
    }

    private suspend fun post(url: String, form: Map<String, String>): JsonObject = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        http.newCall(Request.Builder().url(url).post(body).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            runCatching { json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        }
    }
}

private fun JsonObject.str(key: String): String? =
    this[key]?.jsonPrimitive?.let { if (it.isString) it.content else it.content.takeIf { c -> c != "null" } }

/** Reads files from the Cadence app folder. */
class OneDriveFiles(private val auth: OneDriveAuth) {
    private fun url(rel: String, suffix: String = ""): String {
        val path = rel.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "${OneDriveConfig.APPROOT}:/$path$suffix"
    }

    /** Small files: library.json, book.json. */
    suspend fun bytes(rel: String): ByteArray {
        val token = auth.token()
        return withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url(rel, ":/content")).header("Authorization", "Bearer $token").build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 404) throw GraphException("Not found in OneDrive › Apps › Cadence.", 404)
                if (!resp.isSuccessful) throw GraphException("OneDrive error ${resp.code}.", resp.code)
                resp.body!!.bytes()
            }
        }
    }

    /** A pre-authenticated link (about an hour) — for covers and audio streaming. */
    suspend fun downloadUrl(rel: String): String {
        val token = auth.token()
        return withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url(rel)).header("Authorization", "Bearer $token").build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 404) throw GraphException("Not found in OneDrive › Apps › Cadence.", 404)
                if (!resp.isSuccessful) throw GraphException("OneDrive error ${resp.code}.", resp.code)
                val obj = json.parseToJsonElement(resp.body!!.string()).jsonObject
                obj["@microsoft.graph.downloadUrl"]?.jsonPrimitive?.content
                    ?: throw GraphException("OneDrive didn't give a download link.")
            }
        }
    }
}

