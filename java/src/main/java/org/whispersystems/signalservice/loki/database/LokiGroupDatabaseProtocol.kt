package org.whispersystems.signalservice.loki.database

interface LokiGroupDatabaseProtocol {

    fun updateTitle(groupId: String, title: String)
    fun updateAvatar(groupId: String, avatar: ByteArray)

}
