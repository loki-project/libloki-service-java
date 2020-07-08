package org.whispersystems.signalservice.loki.database

import org.whispersystems.signalservice.loki.api.Snode
import org.whispersystems.signalservice.loki.protocol.multidevice.DeviceLink

interface LokiAPIDatabaseProtocol {

    fun getSnodePool(): Set<Snode>
    fun setSnodePool(newValue: Set<Snode>)
    fun getOnionRequestPaths(): List<List<Snode>>
    fun setOnionRequestPaths(newValue: List<List<Snode>>)
    fun getSwarm(hexEncodedPublicKey: String): Set<Snode>?
    fun setSwarm(hexEncodedPublicKey: String, newValue: Set<Snode>)
    fun getLastMessageHashValue(snode: Snode): String?
    fun setLastMessageHashValue(snode: Snode, newValue: String)
    fun getReceivedMessageHashValues(): Set<String>?
    fun setReceivedMessageHashValues(newValue: Set<String>)
    fun getAuthToken(server: String): String?
    fun setAuthToken(server: String, newValue: String?)
    fun getLastMessageServerID(group: Long, server: String): Long?
    fun setLastMessageServerID(group: Long, server: String, newValue: Long)
    fun getLastDeletionServerID(group: Long, server: String): Long?
    fun setLastDeletionServerID(group: Long, server: String, newValue: Long)
    fun getDeviceLinks(hexEncodedPublicKey: String): Set<DeviceLink>
    fun clearDeviceLinks(hexEncodedPublicKey: String)
    fun addDeviceLink(deviceLink: DeviceLink)
    fun removeDeviceLink(deviceLink: DeviceLink)
    fun setUserCount(userCount: Int, group: Long, server: String)
    fun getSessionRequestTimestamp(publicKey: String): Long?
    fun setSessionRequestTimestamp(publicKey: String, timestamp: Long)
}
