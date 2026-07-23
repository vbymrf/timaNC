package com.tima.client.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.tima.client.network.AttestationProof
import com.tima.client.network.AttestationProvider
import com.tima.client.network.PushTokenProvider
import com.tima.client.platform.PlatformServiceUnavailable
import com.tima.client.platform.SecureSecretStore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PlayIntegrityAttestationProvider(
    context: Context,
    private val cloudProjectNumber: Long = BuildConfig.PLAY_CLOUD_PROJECT_NUMBER,
) : AttestationProvider {
    private val manager = IntegrityManagerFactory.createStandard(context.applicationContext)
    @Volatile
    private var prepared: StandardIntegrityManager.StandardIntegrityTokenProvider? = null

    suspend fun prepare() {
        check(cloudProjectNumber > 0) { "Play Integrity cloud project is not configured" }
        prepared = manager.prepareIntegrityToken(
            StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                .setCloudProjectNumber(cloudProjectNumber)
                .build(),
        ).await()
    }

    override suspend fun attest(action: String, requestBodySha256: ByteArray): AttestationProof {
        require(action.isNotBlank())
        require(requestBodySha256.size == 32)
        val provider = prepared ?: run {
            prepare()
            prepared ?: throw PlatformServiceUnavailable("Play Integrity preparation failed")
        }
        val requestHash = Base64.encodeToString(
            requestBodySha256,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val response = provider.request(
            StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                .setRequestHash(requestHash)
                .build(),
        ).await()
        val token = response.token()
        check(token.isNotBlank()) { "Play Integrity returned an empty token" }
        return AttestationProof(
            endpoint = "/v1/verify/integrity/android",
            body = buildJsonObject {
                put("integrity_token", token)
                put("nonce", Base64.encodeToString(requestBodySha256, Base64.NO_WRAP))
            },
        )
    }
}

class FcmPushTokenProvider(context: Context) : PushTokenProvider {
    private val applicationContext = context.applicationContext
    override val provider = "fcm"

    override suspend fun currentToken(): String {
        check(FirebaseApp.getApps(applicationContext).isNotEmpty()) {
            "Firebase credentials are not configured"
        }
        return FirebaseMessaging.getInstance().token.await()
            .takeIf(String::isNotBlank)
            ?: throw PlatformServiceUnavailable("FCM returned an empty token")
    }
}

class AndroidKeystoreSecretStore(context: Context) : SecureSecretStore {
    private val preferences = context.getSharedPreferences("messnc-secure-secrets", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override suspend fun put(alias: String, secret: ByteArray) {
        validateAlias(alias)
        require(secret.isNotEmpty())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(secret)
        check(preferences.edit().putString(alias, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
        encrypted.fill(0)
    }

    override suspend fun get(alias: String): ByteArray? {
        validateAlias(alias)
        val encoded = preferences.getString(alias, null) ?: return null
        val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
        check(encrypted.size > IV_BYTES) { "invalid encrypted secret" }
        return try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted, 0, IV_BYTES))
                doFinal(encrypted, IV_BYTES, encrypted.size - IV_BYTES)
            }
        } finally {
            encrypted.fill(0)
        }
    }

    override suspend fun delete(alias: String) {
        validateAlias(alias)
        check(preferences.edit().remove(alias).commit())
    }

    private fun key(): SecretKey =
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }.generateKey()

    private fun validateAlias(alias: String) {
        require(alias.matches(Regex("[a-z0-9-]{1,100}"))) { "invalid secret alias" }
    }

    private companion object {
        const val KEY_ALIAS = "messnc-platform-secrets-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
