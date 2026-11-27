package com.example.auth

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.json.JSONObject

object FirebaseBootstrap {
    private const val PREFS_NAME = "firebase_runtime_recovery"
    private const val KEY_RAW_JSON = "google_services_json"
    private const val KEY_WEB_CLIENT_ID = "web_client_id"
    private const val TAG = "FirebaseBootstrap"

    fun ensureInitialized(context: Context): Boolean {
        val appContext = context.applicationContext
        if (FirebaseApp.getApps(appContext).isNotEmpty()) {
            return true
        }
        val defaultInit = try {
            FirebaseApp.initializeApp(appContext)
        } catch (e: Exception) {
            Log.w(TAG, "Default FirebaseApp init unavailable: ${e.message}")
            null
        }
        if (defaultInit != null || FirebaseApp.getApps(appContext).isNotEmpty()) {
            return true
        }
        val savedJson = appContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RAW_JSON, null)
            ?.takeIf { it.isNotBlank() }
            ?: return false

        return initFromGoogleServicesJson(appContext, savedJson, persist = false).isSuccess
    }

    fun initFromGoogleServicesJson(
        context: Context,
        rawJson: String,
        persist: Boolean = true
    ): Result<Unit> {
        return try {
            val appContext = context.applicationContext
            val root = JSONObject(rawJson.trim())
            val projectInfo = root.getJSONObject("project_info")
            val projectId = projectInfo.getString("project_id")
            val projectNumber = projectInfo.optString("project_number")
            val storageBucket = projectInfo.optString("storage_bucket")

            val clients = root.getJSONArray("client")
            var matchedClient: JSONObject? = null
            for (i in 0 until clients.length()) {
                val client = clients.getJSONObject(i)
                val pkg = client.optJSONObject("client_info")
                    ?.optJSONObject("android_client_info")
                    ?.optString("package_name")
                if (pkg == appContext.packageName) {
                    matchedClient = client
                    break
                }
            }
            if (matchedClient == null && clients.length() > 0) {
                matchedClient = clients.getJSONObject(0)
            }
            val clientObj = matchedClient
                ?: return Result.failure(IllegalArgumentException("No Android client entry found in google-services.json"))

            val appId = clientObj.getJSONObject("client_info").getString("mobilesdk_app_id")
            val apiKey = clientObj.getJSONArray("api_key").getJSONObject(0).getString("current_key")

            var webClientId: String? = null
            val oauthClients = clientObj.optJSONArray("oauth_client")
            if (oauthClients != null) {
                for (i in 0 until oauthClients.length()) {
                    val entry = oauthClients.getJSONObject(i)
                    if (entry.optInt("client_type") == 3) {
                        webClientId = entry.optString("client_id").takeIf { it.isNotBlank() }
                        break
                    }
                }
            }

            if (FirebaseApp.getApps(appContext).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApplicationId(appId)
                    .setApiKey(apiKey)
                    .setProjectId(projectId)
                    .apply {
                        if (storageBucket.isNotBlank()) setStorageBucket(storageBucket)
                        if (projectNumber.isNotBlank()) setGcmSenderId(projectNumber)
                    }
                    .build()
                FirebaseApp.initializeApp(appContext, options)
            }

            if (persist) {
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_RAW_JSON, rawJson.trim())
                    .putString(KEY_WEB_CLIENT_ID, webClientId)
                    .apply()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getSavedWebClientId(context: Context): String? {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_WEB_CLIENT_ID, null)
            ?.takeIf { it.isNotBlank() }
    }
}
