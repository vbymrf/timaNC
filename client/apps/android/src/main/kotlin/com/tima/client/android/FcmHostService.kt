package com.tima.client.android

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun interface AndroidPushCallback {
    suspend fun receive(payload: JsonObject)
}

object AndroidPushLifecycle {
    @Volatile
    var callback: AndroidPushCallback? = null
        private set

    fun install(callback: AndroidPushCallback) {
        this.callback = callback
    }

    fun clear() {
        callback = null
    }
}

class MessNcFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val callback = AndroidPushLifecycle.callback
        if (callback == null) {
            Log.w(TAG, "Ignoring FCM wake-up before the authenticated host is ready")
            return
        }
        val payload = runCatching {
            JsonObject(message.data.mapValues { (key, value) ->
                if (key == "encrypted") {
                    JsonPrimitive(value.toBooleanStrict())
                } else {
                    JsonPrimitive(value)
                }
            })
        }.getOrElse {
            Log.e(TAG, "Rejected malformed FCM payload", it)
            return
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            runCatching { callback.receive(payload) }
                .onFailure { Log.e(TAG, "Rejected FCM payload", it) }
        }
    }

    override fun onNewToken(token: String) {
        // Registration is initiated by the authenticated host. Never upload from this OS callback.
        Log.i(TAG, "FCM token rotated; host registration required")
    }

    private companion object {
        const val TAG = "MessNcFcm"
    }
}
