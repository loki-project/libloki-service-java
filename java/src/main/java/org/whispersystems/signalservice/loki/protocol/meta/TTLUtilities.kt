package org.whispersystems.signalservice.loki.protocol.meta

internal object TTLUtilities {

    /**
     * If a message type specifies an invalid TTL, this will be used.
     */
    internal val fallbackMessageTTL = 2 * 24 * 60 * 60 * 1000

    internal enum class MessageType {
        // Unimportant control messages
        Address, SignalServiceCallMessage, TypingIndicator, VerifiedMessage,
        // Somewhat important control messages
        LinkDevice,
        // Important control messages
        Ephemeral, SessionRequest, SignalServiceReceiptMessage, SignalServiceSyncMessage, UnlinkDevice,
        // Visible messages
        FriendRequest, Regular
    }

    @JvmStatic
    internal fun getTTL(messageType: MessageType): Int {
        val minuteInMs = 60 * 1000
        val hourInMs = 60 * minuteInMs
        val dayInMs = 24 * hourInMs
        return when (messageType) {
            // Unimportant control messages
            MessageType.Address, MessageType.SignalServiceCallMessage, MessageType.TypingIndicator, MessageType.VerifiedMessage -> 1 * minuteInMs
            // Somewhat important control messages
            MessageType.LinkDevice -> 1 * hourInMs
            // Important control messages
            MessageType.Ephemeral, MessageType.SessionRequest, MessageType.SignalServiceReceiptMessage,
            MessageType.SignalServiceSyncMessage, MessageType.UnlinkDevice -> 2 * dayInMs - 1 * hourInMs
            // Visible messages
            MessageType.FriendRequest, MessageType.Regular -> 2 * dayInMs
        }
    }
}
