package com.tima.client.windows

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.tima.client.crypto.DeviceIdentity
import com.tima.client.platform.WindowsLinkCoordinator
import com.tima.client.platform.WindowsLinkKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import java.awt.Color
import java.awt.image.BufferedImage
import java.security.SecureRandom
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

fun main() {
    check(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "The MessNC Windows target requires Windows"
    }
    SwingUtilities.invokeLater { WindowsLinkWindow().show() }
}

private class WindowsLinkWindow {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val status = JLabel("Starting secure Windows link…", SwingConstants.CENTER)
    private val qr = JLabel("", SwingConstants.CENTER)
    private val claim = JButton("I approved this device on my phone").apply { isEnabled = false }
    private var coordinator: WindowsLinkCoordinator? = null
    private var sessionId: String? = null

    fun show() {
        val frame = JFrame("MessNC").apply {
            defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
            layout = BorderLayout(12, 12)
            add(status, BorderLayout.NORTH)
            add(qr, BorderLayout.CENTER)
            add(JPanel().apply { add(claim) }, BorderLayout.SOUTH)
            setSize(520, 620)
            setLocationRelativeTo(null)
            isVisible = true
        }
        claim.addActionListener { claimLink() }
        scope.launch {
            runCatching { beginLink() }
                .onFailure { error ->
                    SwingUtilities.invokeLater {
                        status.text = "Link unavailable: ${error.message}"
                        claim.isEnabled = false
                    }
                }
        }
    }

    private suspend fun beginLink() {
        val serviceUrl = System.getenv("TIMA_API_BASE_URL")
            ?.takeIf(String::isNotBlank)
            ?: error("TIMA_API_BASE_URL is required")
        val store = DpapiSecretStore()
        val identity = loadIdentity(store)
        val publicKeys = identity.publicKeys
        val linkCoordinator = WindowsLinkCoordinator(HttpWindowsLinkGateway(serviceUrl), store)
        val challenge = linkCoordinator.start(
            desktopName = System.getenv("COMPUTERNAME") ?: "Windows PC",
            keys = WindowsLinkKeys(publicKeys.x25519, publicKeys.ed25519),
        )
        coordinator = linkCoordinator
        sessionId = challenge.sessionId
        val image = renderQr(challenge.qrPayload)
        SwingUtilities.invokeLater {
            status.text = "Scan this QR in your attested MessNC mobile app"
            qr.icon = ImageIcon(image)
            claim.isEnabled = true
        }
    }

    private fun claimLink() {
        val currentCoordinator = coordinator ?: return
        val currentSession = sessionId ?: return
        claim.isEnabled = false
        status.text = "Claiming approved link…"
        scope.launch {
            runCatching { currentCoordinator.claim(currentSession) }
                .onSuccess {
                    SwingUtilities.invokeLater {
                        status.text = "Windows device linked"
                        qr.icon = null
                    }
                }
                .onFailure { error ->
                    SwingUtilities.invokeLater {
                        status.text = "Not approved yet: ${error.message}"
                        claim.isEnabled = true
                    }
                }
        }
    }

    private fun loadIdentity(store: DpapiSecretStore): DeviceIdentity {
        val seed = runBlocking { store.get(IDENTITY_ALIAS) } ?: ByteArray(32).also {
            SecureRandom().nextBytes(it)
            runBlocking { store.put(IDENTITY_ALIAS, it) }
        }
        return try {
            DeviceIdentity.fromSeed(seed)
        } finally {
            seed.fill(0)
        }
    }

    private fun renderQr(payload: String): BufferedImage {
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 420, 420)
        return BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB).also { image ->
            for (x in 0 until matrix.width) {
                for (y in 0 until matrix.height) {
                    image.setRGB(x, y, if (matrix[x, y]) Color.BLACK.rgb else Color.WHITE.rgb)
                }
            }
        }
    }

    private companion object {
        const val IDENTITY_ALIAS = "windows-device-seed-v1"
    }
}
