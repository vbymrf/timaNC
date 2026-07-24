package com.tima.client.android

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.tima.client.network.PlatformServiceUnavailableException
import com.tima.client.network.WakeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.security.MessageDigest

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runtime: AndroidPhase1Runtime? = null
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            text = "Tima Phase 1 platform shell"
            setPadding(0, 0, 0, 32)
        }
        val prepare = Button(this).apply {
            text = "Prepare device trust"
            setOnClickListener { prepareTrust() }
        }
        val registerPush = Button(this).apply {
            text = "Register FCM token"
            setOnClickListener {
                runPlatformOperation("Push token registered") {
                    phase1.registerCurrentPushToken()
                }
            }
        }
        val registerUnifiedPush = Button(this).apply {
            text = "Register UnifiedPush endpoint"
            setOnClickListener {
                runPlatformOperation("UnifiedPush endpoint registered") {
                    unifiedPushPhase1.registerCurrentPushToken()
                }
            }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val padding = (24 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
                addView(status)
                addView(prepare)
                addView(registerPush)
                addView(registerUnifiedPush)
            },
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        runtime = runCatching { configuredRuntime() }.getOrElse {
            status.text = "Blocked: ${it.message}"
            prepare.isEnabled = false
            registerPush.isEnabled = false
            registerUnifiedPush.isEnabled = false
            null
        }
        scope.launch { runtime?.restoreSession() }
    }

    override fun onResume() {
        super.onResume()
        AndroidNotificationWakeBridge.wake(WakeSource.APP_RESUME)
    }

    private fun prepareTrust() {
        runPlatformOperation("Play Integrity is ready") {
            integrity.prepare()
            // A real vendor token is requested only for an exact API body.
            phase1.attestationToken(
                action = "platform-shell-check",
                requestBodySha256 = MessageDigest.getInstance("SHA-256").digest("{}".toByteArray()),
            )
        }
    }

    private fun runPlatformOperation(success: String, block: suspend AndroidPhase1Runtime.() -> Unit) {
        val current = runtime ?: return
        status.text = "Working…"
        scope.launch {
            status.text = runCatching {
                current.block()
                success
            }.fold(
                onSuccess = { it },
                onFailure = {
                    val prefix = if (it is PlatformServiceUnavailableException) "Blocked" else "Failed"
                    "$prefix: ${it.message}"
                },
            )
        }
    }

    private fun configuredRuntime(): AndroidPhase1Runtime {
        @Suppress("DEPRECATION")
        val info = packageManager.getApplicationInfo(
            packageName,
            PackageManager.GET_META_DATA,
        )
        val baseUrl = info.metaData.get("com.tima.BASE_URL")?.toString().orEmpty()
        val projectNumber = info.metaData.get("com.tima.INTEGRITY_PROJECT_NUMBER")
            ?.toString()?.toLongOrNull() ?: 0L
        return AndroidPhase1Runtime(this, baseUrl, projectNumber)
    }

    override fun onDestroy() {
        runtime?.close()
        scope.cancel()
        super.onDestroy()
    }
}
