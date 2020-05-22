package org.whispersystems.signalservice.loki.database

import org.whispersystems.signalservice.loki.protocol.todo.LokiMessageFriendRequestStatus

interface LokiMessageDatabaseProtocol {

    fun getQuoteServerID(quoteID: Long, quoteeHexEncodedPublicKey: String): Long?
    fun setServerID(messageID: Long, serverID: Long)
    fun setFriendRequestStatus(messageID: Long, friendRequestStatus: LokiMessageFriendRequestStatus)
}
