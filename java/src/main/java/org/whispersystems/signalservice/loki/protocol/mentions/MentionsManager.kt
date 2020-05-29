package org.whispersystems.signalservice.loki.protocol.mentions

import org.whispersystems.signalservice.loki.database.LokiThreadDatabaseProtocol
import org.whispersystems.signalservice.loki.database.LokiUserDatabaseProtocol

class MentionsManager(private val userHexEncodedPublicKey: String, private val threadDatabase: LokiThreadDatabaseProtocol,
        private val userDatabase: LokiUserDatabaseProtocol) {
    var userPublicKeyCache = mutableMapOf<Long, Set<String>>() // Thread ID to set of user hex encoded public keys

    companion object {

        public lateinit var shared: MentionsManager

        public fun configureIfNeeded(userHexEncodedPublicKey: String, threadDatabase: LokiThreadDatabaseProtocol, userDatabase: LokiUserDatabaseProtocol) {
            if (::shared.isInitialized) { return; }
            shared = MentionsManager(userHexEncodedPublicKey, threadDatabase, userDatabase)
        }
    }

    fun cache(hexEncodedPublicKey: String, threadID: Long) {
        val cache = userPublicKeyCache[threadID]
        if (cache != null) {
            userPublicKeyCache[threadID] = cache.plus(hexEncodedPublicKey)
        } else {
            userPublicKeyCache[threadID] = setOf( hexEncodedPublicKey )
        }
    }

    fun getMentionCandidates(query: String, threadID: Long): List<Mention> {
        // Prepare
        val cache = userPublicKeyCache[threadID] ?: return listOf()
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
