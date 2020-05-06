package org.whispersystems.signalservice.loki.protocol.mentions

import org.whispersystems.signalservice.loki.database.LokiThreadDatabaseProtocol
import org.whispersystems.signalservice.loki.database.LokiUserDatabaseProtocol

public object MentionsManager {
    var userHexEncodedPublicKeyCache = mutableMapOf<Long, Set<String>>() // Thread ID to set of user hex encoded public keys

    fun cache(hexEncodedPublicKey: String, threadID: Long) {
        val cache = userHexEncodedPublicKeyCache[threadID]
        if (cache != null) {
            userHexEncodedPublicKeyCache[threadID] = cache.plus(hexEncodedPublicKey)
        } else {
            userHexEncodedPublicKeyCache[threadID] = setOf( hexEncodedPublicKey )
        }
    }

    fun getMentionCandidates(query: String, threadID: Long, userHexEncodedPublicKey: String, threadDatabase: LokiThreadDatabaseProtocol, userDatabase: LokiUserDatabaseProtocol): List<Mention> {
        // Prepare
        val cache = userHexEncodedPublicKeyCache[threadID] ?: return listOf()
        // Gather candidates
        val publicChat = threadDatabase.getPublicChat(threadID)
        var candidates: List<Mention> = cache.mapNotNull { hexEncodedPublicKey ->
            val displayName: String?
            if (publicChat != null) {
                displayName = userDatabase.getServerDisplayName(publicChat.id, hexEncodedPublicKey)
            } else {
                displayName = userDatabase.getDisplayName(hexEncodedPublicKey)
            }
            if (displayName == null) { return@mapNotNull null }
            if (displayName.startsWith("Anonymous")) { return@mapNotNull null }
            Mention(hexEncodedPublicKey, displayName)
        }
        candidates = candidates.filter { it.hexEncodedPublicKey != userHexEncodedPublicKey }
        // Sort alphabetically first
        candidates.sortedBy { it.displayName }
        if (query.length >= 2) {
            // Filter out any non-matching candidates
            candidates = candidates.filter { it.displayName.toLowerCase().contains(query.toLowerCase()) }
            // Sort based on where in the candidate the query occurs
            candidates.sortedBy { it.displayName.toLowerCase().indexOf(query.toLowerCase()) }
        }
        // Return
        return candidates
    }
}
