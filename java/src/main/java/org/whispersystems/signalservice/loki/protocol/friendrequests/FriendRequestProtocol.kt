package org.whispersystems.signalservice.loki.protocol.friendrequests

import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.loki.database.LokiAPIDatabaseProtocol
import org.whispersystems.signalservice.loki.protocol.multidevice.DeviceLink

public class FriendRequestProtocol(private val apiDatabase: LokiAPIDatabaseProtocol, private val userPublicKey: String) {

    // region Initialization
    companion object {

        public lateinit var shared: FriendRequestProtocol

        public fun configureIfNeeded(apiDatabase: LokiAPIDatabaseProtocol, userPublicKey: String) {
            if (::shared.isInitialized) { return; }
            shared = FriendRequestProtocol(apiDatabase, userPublicKey)
        }
    }
    // endregion

    // region Sending
    public fun shouldUpdateFriendRequestStatusFromMessage(message: SignalServiceDataMessage, recipient: String): Boolean {
        // The order of these checks matters
        if (message.isGroupMessage) { return false }
        if (recipient == userPublicKey) { return false }
        if (message.deviceLink.isPresent && message.deviceLink.get().type == DeviceLink.Type.REQUEST) { return true }
        if (message.isSessionRequest) { return false }
        return message.isFriendRequest
    }
    // endregion
}
