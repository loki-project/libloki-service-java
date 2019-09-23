package org.whispersystems.signalservice.loki.api

class LokiDeviceLinkingSession() {
    companion object {
        val shared = LokiDeviceLinkingSession()
    }

    var isListeningForLinkingRequest: Boolean = false
        private set
    private val listeners = mutableListOf<LokiDeviceLinkingSessionListener>()

    fun addListener(listener: LokiDeviceLinkingSessionListener) { listeners.add(listener) }
    fun removeListener(listener: LokiDeviceLinkingSessionListener) { listeners.remove(listener) }

    fun startListeningForLinkingRequests() {
        isListeningForLinkingRequest = true
    }

    fun receivedLinkingRequest(authorisation: LokiPairingAuthorisation) {
        if (!isListeningForLinkingRequest) { return }
        listeners.forEach { it.onDeviceLinkingRequestReceived(authorisation) }
    }

    fun stopListeningForLinkingRequests() {
        isListeningForLinkingRequest = false
    }
}