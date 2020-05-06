package org.whispersystems.signalservice.loki.database

interface LokiUserDatabaseProtocol {

    fun getDisplayName(hexEncodedPublicKey: String): String?
    fun getServerDisplayName(serverID: String, hexEncodedPublicKey: String): String?
    fun getProfilePictureURL(hexEncodedPublicKey: String): String?
}
