package com.tima.client.windows

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

fun main() {
    SwingUtilities.invokeLater {
        val baseUrl = System.getenv("TIMA_API_BASE_URL").orEmpty()
        val runtime = runCatching { WindowsPhase1Runtime(baseUrl) }.getOrElse {
            showBlockedShell(it.message ?: "platform services unavailable")
            return@invokeLater
        }
        WindowsShell(runtime).show()
    }
}

private class WindowsShell(
    private val runtime: WindowsPhase1Runtime,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val status = JLabel("Start linking, then scan the QR code with a trusted mobile device.")
    private val qr = JLabel()
    private val claim = JButton("Claim confirmed link").apply { isEnabled = false }
    private val frame = JFrame("Tima")

    fun show() {
        val start = JButton("Start Windows link").apply {
            addActionListener {
                isEnabled = false
                runOperation(
                    operation = { runtime.linking.start(System.getProperty("user.name") + " Windows") },
                    success = { pending ->
                        qr.icon = ImageIcon(qrImage(pending.qrPayload))
                        qr.toolTipText = pending.qrPayload
                        status.text = "Scan before ${pending.expiresAt}; no QR secret is logged."
                        claim.isEnabled = true
                    },
                    failure = { isEnabled = true },
                )
            }
        }
        claim.addActionListener {
            claim.isEnabled = false
            runOperation(
                operation = runtime::claimLink,
                success = { session ->
                    qr.icon = null
                    status.text = "Linked device ${session.deviceId}; credentials protected with DPAPI."
                },
                failure = { claim.isEnabled = true },
            )
        }
        val controls = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(start)
            add(claim)
        }
        frame.contentPane = JPanel(BorderLayout(12, 12)).apply {
            border = BorderFactory.createEmptyBorder(20, 20, 20, 20)
            add(status, BorderLayout.NORTH)
            add(qr, BorderLayout.CENTER)
            add(controls, BorderLayout.SOUTH)
        }
        frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(event: WindowEvent) {
                runtime.close()
                scope.cancel()
            }
        })
        frame.setSize(520, 520)
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        scope.launch { runtime.restoreSession() }
    }

    private fun <T> runOperation(
        operation: suspend () -> T,
        success: (T) -> Unit,
        failure: () -> Unit,
    ) {
        status.text = "Working…"
        scope.launch {
            runCatching { operation() }.fold(
                onSuccess = { value -> SwingUtilities.invokeLater { success(value) } },
                onFailure = { error ->
                    SwingUtilities.invokeLater {
                        status.text = "Blocked: ${error.message}"
                        failure()
                    }
                },
            )
        }
    }
}

private fun qrImage(payload: String): BufferedImage {
    require(payload.isNotBlank())
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 360, 360)
    return BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB).apply {
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                setRGB(x, y, if (matrix[x, y]) Color.BLACK.rgb else Color.WHITE.rgb)
            }
        }
    }
}

private fun showBlockedShell(message: String) {
    JFrame("Tima").apply {
        contentPane.add(
            JLabel("<html><body style='padding:16px'>Blocked: $message</body></html>"),
        )
        defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        setSize(480, 160)
        setLocationRelativeTo(null)
        isVisible = true
    }
}
