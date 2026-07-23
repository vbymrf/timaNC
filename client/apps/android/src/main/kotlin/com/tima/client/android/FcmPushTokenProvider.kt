package com.tima.client.android

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.tima.client.network.PlatformServiceUnavailableException
import com.tima.client.network.PushTokenProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Firebase initialization remains an application-lifecycle responsibility
 * (normally google-services.json or explicit FirebaseOptions).
 */
class FcmPushTokenProvider(context: Context) : PushTokenProvider {
    private val applicationContext = context.applicationContext

    override val provider: String = "fcm"

    override suspend fun currentToken(): String {
        if (FirebaseApp.getApps(applicationContext).isEmpty()) {
            throw PlatformServiceUnavailableException(
                "Firebase Messaging (initialize FirebaseApp in the host application first)",
            )
        }
        val token = runCatching { FirebaseMessaging.getInstance().token.awaitFcm() }
            .getOrElse { throw PlatformServiceUnavailableException("Firebase Messaging", it) }
        check(token.isNotBlank()) { "Firebase Messaging returned an empty token" }
        return token
    }
}

private suspend fun <T> Task<T>.awaitFcm(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value ->
            if (continuation.isActive) continuation.resume(value)
        }
        addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        addOnCanceledListener { continuation.cancel() }
    }
