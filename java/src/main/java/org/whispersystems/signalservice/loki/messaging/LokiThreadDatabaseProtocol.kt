package org.whispersystems.signalservice.loki.messaging

import org.whispersystems.signalservice.loki.api.publicchats.LokiPublicChat

interface LokiThreadDatabaseProtocol {

    fun getThreadID(hexEncodedPublicKey: String): Long
    fun setFriendRequestStatus(threadID: Long, friendRequestStatus: LokiThreadFriendRequestStatus)
    fun getPublicChat(threadID: Long): LokiPublicChat?
    fun setPublicChat(publicChat: LokiPublicChat, threadID: Long)
    fun removePublicChat(threadID: Long)
}
