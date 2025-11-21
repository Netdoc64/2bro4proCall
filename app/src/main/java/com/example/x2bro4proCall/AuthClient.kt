package com.example.x2bro4proCall

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class AuthClient(private val context: Context, private val baseUrl: String) {
    private val client = OkHttpClient()
    private val prefs: SharedPreferences by lazy { createEncryptedPrefs() }

    companion object {
        private const val PREF_FILE = "secure_prefs"
        private const val KEY_TOKEN = "jwt_token"
        private const val KEY_DOMAINS = "jwt_domains"
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            PREF_FILE,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    interface LoginCallback {
        fun onSuccess(token: String, role: String?, domains: List<String>)
        fun onFailure(message: String)
    }

    interface RegisterCallback {
        fun onSuccess(token: String, role: String?, domains: List<String>)
        fun onFailure(message: String)
    }

    fun register(name: String?, email: String, password: String, cb: RegisterCallback) {
        val url = "$baseUrl/api/register"
        val json = JSONObject().apply {
            put("email", email)
            put("password", password)
            if (!name.isNullOrBlank()) put("name", name)
        }
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toString().toRequestBody(mediaType)
        val req = Request.Builder().url(url).post(body).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cb.onFailure("Network error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        cb.onFailure("Register failed: ${it.code}")
                        return
                    }
                    val text = it.body?.string() ?: ""
                    try {
                        val json = JSONObject(text)
                        val token = json.optString("token", "")
                        val role = json.optString("role", null)
                        val domainsJson = json.optJSONArray("domains")
                        val domains = mutableListOf<String>()
                        if (domainsJson != null) {
                            for (i in 0 until domainsJson.length()) {
                                domains.add(domainsJson.optString(i))
                            }
                        }
                        if (token.isNotBlank()) {
                            saveToken(token)
                            saveDomains(domains)
                            cb.onSuccess(token, role, domains)
                        } else {
                            // Some backends return success without token for register
                            cb.onFailure("Register succeeded but no token returned")
                        }
                    } catch (e: Exception) {
                        cb.onFailure("Invalid response: ${e.message}")
                    }
                }
            }
        })
    }

    fun login(email: String, password: String, cb: LoginCallback) {
        val url = "$baseUrl/api/login"
        val json = JSONObject().apply {
            put("email", email)
            put("password", password)
        }
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toString().toRequestBody(mediaType)
        val req = Request.Builder().url(url).post(body).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cb.onFailure("Network error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        cb.onFailure("Login failed: ${it.code}")
                        return
                    }
                    val text = it.body?.string() ?: ""
                    try {
                        val json = JSONObject(text)
                        val token = json.optString("token", "")
                        val role = json.optString("role", null)
                        val domainsJson = json.optJSONArray("domains")
                        val domains = mutableListOf<String>()
                        if (domainsJson != null) {
                            for (i in 0 until domainsJson.length()) {
                                domains.add(domainsJson.optString(i))
                            }
                        }
                        if (token.isNotBlank()) {
                            saveToken(token)
                            saveDomains(domains)
                            cb.onSuccess(token, role, domains)
                        } else {
                            cb.onFailure("Login succeeded but no token returned")
                        }
                    } catch (e: Exception) {
                        cb.onFailure("Invalid response: ${e.message}")
                    }
                }
            }
        })
    }

    private fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    private fun saveDomains(domains: List<String>) {
        prefs.edit().putString(KEY_DOMAINS, JSONObject().put("domains", domains).toString()).apply()
    }

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun getDomains(): List<String> {
        val text = prefs.getString(KEY_DOMAINS, null) ?: return emptyList()
        return try {
            val json = JSONObject(text)
            val arr = json.optJSONArray("domains") ?: return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) out.add(arr.optString(i))
            out
        } catch (e: Exception) {
            Log.e("AuthClient", "getDomains parse failed: ${e.message}")
            emptyList()
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
