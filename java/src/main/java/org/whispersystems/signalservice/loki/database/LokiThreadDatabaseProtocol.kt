package org.whispersystems.signalservice.loki.database

import org.whispersystems.signalservice.loki.api.opengroups.LokiPublicChat
import org.whispersystems.signalservice.loki.protocol.todo.LokiThreadFriendRequestStatus

interface LokiThreadDatabaseProtocol {

    fun getThreadID(hexEncodedPublicKey: String): Long
    fun setFriendRequestStatus(threadID: Long, friendRequestStatus: LokiThreadFriendRequestStatus)
    fun getPublicChat(threadID: Long): LokiPublicChat?
    fun setPublicChat(publicChat: LokiPublicChat, threadID: Long)
    fun removePublicChat(threadID: Long)
    fun isClosedGroup(threadID: Long): Boolean
}
