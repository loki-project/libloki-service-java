package org.whispersystems.signalservice.loki.messaging

import org.whispersystems.signalservice.api.push.SignalServiceAddress

data class LokiSyncMessage(
    public val recipient: SignalServiceAddress,
    public val originalMessageID: Long
)