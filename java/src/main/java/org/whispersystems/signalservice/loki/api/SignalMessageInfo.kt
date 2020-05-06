package org.whispersystems.signalservice.loki.api

import org.whispersystems.signalservice.internal.push.SignalServiceProtos

data class SignalMessageInfo(
    val type: SignalServiceProtos.Envelope.Type,
    val timestamp: Long,
    val senderID: String,
    val senderDeviceID: Int,
    val content: String,
    val recipientID: String,
    val ttl: Int?,
    val isPing: Boolean
)
