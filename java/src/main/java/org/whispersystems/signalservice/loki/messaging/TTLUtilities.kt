package org.whispersystems.signalservice.loki.messaging

internal object TTLUtilities {

    /**
     * If a message type specifies an invalid TTL, this will be used.
     */
    internal val fallbackMessageTTL = 4 * 24 * 60 * 60 * 1000

    internal enum class MessageType {
        Address, // TODO: Unused?
        Ephemeral, // TODO: Unused?
        FriendRequest,
        LinkDevice,
        Regular,
        SessionRequest,
        TypingIndicator,
        UnlinkDevice
    }

    @JvmStatic
    internal fun getTTL(messageType: MessageType): Int {
        val minuteInMs = 60 * 1000
        val hourInMs = 60 * minuteInMs
        val dayInMs = 24 * hourInMs
        return when (messageType) {
            MessageType.Address -> 1 * minuteInMs
            MessageType.Ephemeral -> 4 * dayInMs - 1 * hourInMs
            MessageType.FriendRequest -> 4 * dayInMs
            MessageType.LinkDevice -> 4 * minuteInMs
            MessageType.Regular -> 2 * dayInMs
            MessageType.SessionRequest -> 4 * dayInMs - 1 * hourInMs
            MessageType.TypingIndicator -> 1 * minuteInMs
            MessageType.UnlinkDevice -> 4 * dayInMs
        }
    }
}
