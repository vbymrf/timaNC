package com.tima.client.data

import com.tima.client.crypto.DeviceIdentity
import com.tima.client.crypto.MessengerCrypto
import com.tima.client.domain.DevicePublicKeys
import com.tima.client.domain.DocumentMetadata
import com.tima.client.domain.EnvelopeHeader
import com.tima.client.domain.PlainTextDocumentV2
import com.tima.client.domain.VerifiedEscrowConfig
import com.tima.client.network.PrivateMessageHistoryDto
import com.tima.client.network.RestCryptoTransportAdapter
import com.tima.client.network.ReservedMessageIds

fun interface DeviceIdentityProvider {
    suspend fun current(): DeviceIdentity?
}

fun interface RecipientDeviceDirectory {
    suspend fun devicesForChat(chatId: String): Map<String, DevicePublicKeys>?
}

fun interface SenderKeyDirectory {
    suspend fun keys(senderUserId: String, senderDeviceId: String): DevicePublicKeys?
}

fun interface VerifiedEscrowConfigProvider {
    suspend fun forPrivateChat(chatId: String): VerifiedEscrowConfig?
}

/**
 * Production Path-B orchestration over MessengerCrypto. Missing trust material blocks the write;
 * there is no unsigned, unencrypted, or development-escrow fallback.
 */
class ProductionPrivateMessageCrypto(
    private val messengerCrypto: MessengerCrypto,
    private val sessions: SessionRepository,
    private val identities: DeviceIdentityProvider,
    private val recipientDirectory: RecipientDeviceDirectory,
    private val senderDirectory: SenderKeyDirectory,
    private val escrowConfigs: VerifiedEscrowConfigProvider,
) : PrivateMessageCrypto {
    override suspend fun encrypt(
        chatId: String,
        text: String,
        reservation: ReservedMessageIds,
        parentRevisionId: String?,
        revisionNumber: ULong,
    ) = run {
        require(text.isNotBlank()) { "message text must not be blank" }
        require(revisionNumber in 1uL..(UInt.MAX_VALUE.toULong() + 1uL)) {
            "revision number exceeds message-key identifier range"
        }
        val session = sessions.current()
        val identity = identities.current()
        val recipients = recipientDirectory.devicesForChat(chatId)
        val escrow = escrowConfigs.forPrivateChat(chatId)
        val missing = buildSet {
            if (session == null) add("authenticated session")
            if (identity == null) add("device identity")
            if (recipients.isNullOrEmpty()) add("recipient device directory")
            if (escrow == null) add("verified escrow config")
        }
        if (missing.isNotEmpty()) throw MissingEncryptionConfigurationException(missing)
        check(recipients!!.containsKey(session!!.deviceId)) {
            "recipient directory must include the sending device"
        }
        val envelope = messengerCrypto.encryptTextMessage(
            sender = identity!!,
            header = EnvelopeHeader(
                messageId = reservation.messageId,
                revisionId = reservation.revisionId,
                parentRevisionId = parentRevisionId,
                chatId = chatId,
                senderId = session.userId,
                senderDeviceId = session.deviceId,
                messageKeyId = (revisionNumber - 1uL).toUInt(),
            ),
            document = PlainTextDocumentV2(
                textNodes = listOf(text),
                metadata = DocumentMetadata(revisionNumber = revisionNumber),
            ),
            recipientDevices = recipients,
            escrowConfig = escrow!!,
        )
        RestCryptoTransportAdapter.toTransport(envelope)
    }

    override suspend fun decrypt(value: PrivateMessageHistoryDto): DecryptedMessage {
        val session = sessions.current()
        val identity = identities.current()
        val senderKeys = senderDirectory.keys(value.sender_id, value.sender_device_id)
        val missing = buildSet {
            if (session == null) add("authenticated session")
            if (identity == null) add("device identity")
            if (senderKeys == null) add("sender signing directory")
        }
        if (missing.isNotEmpty()) throw MissingEncryptionConfigurationException(missing)
        val document = messengerCrypto.decryptTextMessageViaPathB(
            recipientDeviceId = session!!.deviceId,
            recipient = identity!!,
            senderPublicKeys = senderKeys!!,
            envelope = RestCryptoTransportAdapter.fromHistory(value),
        )
        return DecryptedMessage(
            text = document.textNodes.joinToString("\n"),
            revisionNumber = document.metadata.revisionNumber,
        )
    }
}
