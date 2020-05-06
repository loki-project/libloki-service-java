package org.whispersystems.signalservice.loki.protocol.todo

enum class LokiThreadFriendRequestStatus(val rawValue: Int) {
    NONE(0),
    REQUEST_SENDING(1),
    REQUEST_SENT(2),
    REQUEST_RECEIVED(3),
    FRIENDS(4),
    REQUEST_EXPIRED(5)
}
