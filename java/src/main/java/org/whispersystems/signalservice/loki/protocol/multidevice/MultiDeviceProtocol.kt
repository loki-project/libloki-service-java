package org.whispersystems.signalservice.loki.protocol.multidevice

import org.whispersystems.signalservice.loki.database.LokiAPIDatabaseProtocol

public class MultiDeviceProtocol(private val apiDatabase: LokiAPIDatabaseProtocol) {

    // region Initialization
    companion object {

        public lateinit var shared: MultiDeviceProtocol

        public fun configureIfNeeded(apiDatabase: LokiAPIDatabaseProtocol) {
            if (::shared.isInitialized) { return; }
            shared = MultiDeviceProtocol(apiDatabase)
        }
    }
    // endregion

    // region Utilities
    public fun getMasterDevice(hexEncodedPublicKey: String): String? {
        val deviceLinks = apiDatabase.getDeviceLinks(hexEncodedPublicKey)
        return deviceLinks.firstOrNull { it.slaveHexEncodedPublicKey == hexEncodedPublicKey }?.masterHexEncodedPublicKey
    }

    public fun getSlaveDevices(hexEncodedPublicKey: String): Set<String> {
        val deviceLinks = apiDatabase.getDeviceLinks(hexEncodedPublicKey)
        if (deviceLinks.isEmpty()) { return setOf() }
        return deviceLinks.map { it.slaveHexEncodedPublicKey }.toSet()
    }

    public fun getAllLinkedDevices(hexEncodedPublicKey: String): Set<String> {
        val deviceLinks = apiDatabase.getDeviceLinks(hexEncodedPublicKey)
        if (deviceLinks.isEmpty()) { return setOf( hexEncodedPublicKey ) }
        return deviceLinks.flatMap { listOf( it.masterHexEncodedPublicKey, it.slaveHexEncodedPublicKey ) }.toSet()
    }
    // endregion
}
