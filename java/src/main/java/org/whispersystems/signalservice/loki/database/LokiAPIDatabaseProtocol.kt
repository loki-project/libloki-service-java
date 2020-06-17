package org.whispersystems.signalservice.loki.database

import org.whispersystems.signalservice.loki.api.LokiAPITarget
import org.whispersystems.signalservice.loki.protocol.multidevice.DeviceLink

interface LokiAPIDatabaseProtocol {

    fun getSnodePool(): Set<LokiAPITarget>
    fun setSnodePool(newValue: Set<LokiAPITarget>)
    fun getOnionRequestPaths(): List<List<LokiAPITarget>>
    fun setOnionRequestPaths(newValue: List<List<LokiAPITarget>>)
    fun getSwarm(hexEncodedPublicKey: String): Set<LokiAPITarget>?
    fun setSwarm(hexEncodedPublicKey: String, newValue: Set<LokiAPITarget>)
    fun getLastMessageHashValue(target: LokiAPITarget): String?
    fun setLastMessageHashValue(target: LokiAPITarget, newValue: String)
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
    fun setOpenGroupAvatarURL(url: String, group: Long, server: String)
    fun getOpenGroupAvatarURL(group: Long, server: String): String?
    fun getSessionRequestTimestamp(publicKey: String): Long?
    fun setSessionRequestTimestamp(publicKey: String, timestamp: Long)
}
