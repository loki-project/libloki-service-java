package org.whispersystems.signalservice.loki.api.opengroups

import nl.komponents.kovenant.Kovenant
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred
import nl.komponents.kovenant.functional.map
import nl.komponents.kovenant.then
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.signalservice.internal.util.Base64
import org.whispersystems.signalservice.internal.util.Hex
import org.whispersystems.signalservice.internal.util.JsonUtil
import org.whispersystems.signalservice.loki.api.SnodeAPI
import org.whispersystems.signalservice.loki.api.LokiDotNetAPI
import org.whispersystems.signalservice.loki.database.LokiAPIDatabaseProtocol
import org.whispersystems.signalservice.loki.database.LokiUserDatabaseProtocol
import org.whispersystems.signalservice.loki.utilities.createContext
import org.whispersystems.signalservice.loki.utilities.retryIfNeeded
import java.text.SimpleDateFormat
import java.util.*

class LokiPublicChatAPI(private val userPublicKey: String, private val userPrivateKey: ByteArray, private val apiDatabase: LokiAPIDatabaseProtocol, private val userDatabase: LokiUserDatabaseProtocol) : LokiDotNetAPI(userPublicKey, userPrivateKey, apiDatabase) {

    companion object {
        private val moderators: HashMap<String, HashMap<Long, Set<String>>> = hashMapOf() // Server URL to (channel ID to set of moderator IDs)
        val sharedContext = Kovenant.createContext("LokiPublicChatAPISharedContext")

        // region Settings
        private val fallbackBatchCount = 16
        private val maxRetryCount = 8
        // endregion

        // region Convenience
        private val channelInfoType = "net.patter-app.settings"
        private val attachmentType = "net.app.core.oembed"
        @JvmStatic
        public val publicChatMessageType = "network.loki.messenger.publicChat"
        @JvmStatic
        public val profilePictureType = "network.loki.messenger.avatar"

        fun getDefaultChats(isDebug: Boolean = false): List<LokiPublicChat> {
            return listOf() // Don't auto-join any open groups right now
        }

        public fun isUserModerator(hexEncodedPublicKey: String, channel: Long, server: String): Boolean {
            if (moderators[server] != null && moderators[server]!![channel] != null) {
                return moderators[server]!![channel]!!.contains(hexEncodedPublicKey)
            }
            return false
        }
        // endregion
    }

    // region Public API
    public fun getMessages(channel: Long, server: String): Promise<List<LokiPublicChatMessage>, Exception> {
        Log.d("Loki", "Getting messages for open group with ID: $channel on server: $server.")
        val parameters = mutableMapOf<String, Any>( "include_annotations" to 1 )
        val lastMessageServerID = apiDatabase.getLastMessageServerID(channel, server)
        if (lastMessageServerID != null) {
            parameters["since_id"] = lastMessageServerID
        } else {
            parameters["count"] = fallbackBatchCount
        }
        return execute(HTTPVerb.GET, server, "channels/$channel/messages", parameters = parameters).then(sharedContext) { response ->
            try {
                val bodyAsString = response.body!!
                val body = JsonUtil.fromJson(bodyAsString)
                val data = body.get("data")
                val messages = data.mapNotNull { message ->
                    try {
                        val isDeleted = message.has("is_deleted") && message.get("is_deleted").asBoolean(false)
                        if (isDeleted) { return@mapNotNull null }
                        // Ignore messages without annotations
                        if (!message.hasNonNull("annotations")) { return@mapNotNull null }
                        val annotation = message.get("annotations").find {
                            (it.get("type").asText("") == publicChatMessageType) && it.hasNonNull("value")
                        } ?: return@mapNotNull null
                        val value = annotation.get("value")
                        val serverID = message.get("id").asLong()
                        val user = message.get("user")
                        val hexEncodedPublicKey = user.get("username").asText()
                        val displayName = if (user.hasNonNull("name")) user.get("name").asText() else "Anonymous"
                        var profilePicture: LokiPublicChatMessage.ProfilePicture? = null
                        if (user.hasNonNull("annotations")) {
                            val profilePictureAnnotation = user.get("annotations").find {
                                (it.get("type").asText("") == profilePictureType) && it.hasNonNull("value")
                            }
                            val profilePictureAnnotationValue = profilePictureAnnotation?.get("value")
                            if (profilePictureAnnotationValue != null && profilePictureAnnotationValue.hasNonNull("profileKey") && profilePictureAnnotationValue.hasNonNull("url")) {
                                try {
                                    val profileKey = Base64.decode(profilePictureAnnotationValue.get("profileKey").asText())
                                    val url = profilePictureAnnotationValue.get("url").asText()
                                    profilePicture = LokiPublicChatMessage.ProfilePicture(profileKey, url)
                                } catch (e: Exception) {}
                            }
                        }
                        @Suppress("NAME_SHADOWING") val body = message.get("text").asText()
                        val timestamp = value.get("timestamp").asLong()
                        var quote: LokiPublicChatMessage.Quote? = null
                        if (value.hasNonNull("quote")) {
                            val replyTo = if (message.hasNonNull("reply_to")) message.get("reply_to").asLong() else null
                            val quoteAnnotation = value.get("quote")
                            val quoteTimestamp = quoteAnnotation.get("id").asLong()
                            val author = quoteAnnotation.get("author").asText()
                            val text = quoteAnnotation.get("text").asText()
                            quote = if (quoteTimestamp > 0L && author != null && text != null) LokiPublicChatMessage.Quote(quoteTimestamp, author, text, replyTo) else null
                        }
                        val attachmentsAsJSON = message.get("annotations").filter { (it.get("type").asText("") == attachmentType) && it.hasNonNull("value") }
                        val attachments = attachmentsAsJSON.map { it.get("value") }.mapNotNull { attachmentAsJSON ->
                            try {
                                val kindAsString = attachmentAsJSON.get("lokiType").asText()
                                val kind = LokiPublicChatMessage.Attachment.Kind.values().first { it.rawValue == kindAsString }
                                val id = attachmentAsJSON.get("id").asLong()
                                val contentType = attachmentAsJSON.get("contentType").asText()
                                val size = attachmentAsJSON.get("size").asInt()
                                val fileName = attachmentAsJSON.get("fileName").asText()
                                val flags = 0
                                val width = attachmentAsJSON.get("width").asInt()
                                val height = attachmentAsJSON.get("height").asInt()
                                val url = attachmentAsJSON.get("url").asText()
                                val caption = if (attachmentAsJSON.hasNonNull("caption")) attachmentAsJSON.get("caption").asText() else null
                                val linkPreviewURL = if (attachmentAsJSON.hasNonNull("linkPreviewUrl")) attachmentAsJSON.get("linkPreviewUrl").asText() else null
                                val linkPreviewTitle = if (attachmentAsJSON.hasNonNull("linkPreviewTitle")) attachmentAsJSON.get("linkPreviewTitle").asText() else null
                                if (kind == LokiPublicChatMessage.Attachment.Kind.LinkPreview && (linkPreviewURL == null || linkPreviewTitle == null)) {
                                    null
                                } else {
                                    LokiPublicChatMessage.Attachment(kind, server, id, contentType, size, fileName, flags, width, height, caption, url, linkPreviewURL, linkPreviewTitle)
                                }
                            } catch (e: Exception) {
                                null
                            }
                        }
                        // Set the last message server ID here to avoid the situation where a message doesn't have a valid signature and this function is called over and over
                        @Suppress("NAME_SHADOWING") val lastMessageServerID = apiDatabase.getLastMessageServerID(channel, server)
                        if (serverID > lastMessageServerID ?: 0) { apiDatabase.setLastMessageServerID(channel, server, serverID) }
                        val hexEncodedSignature = value.get("sig").asText()
                        val signatureVersion = value.get("sigver").asLong()
                        val signature = LokiPublicChatMessage.Signature(Hex.fromStringCondensed(hexEncodedSignature), signatureVersion)
                        // Verify the message
                        val groupMessage = LokiPublicChatMessage(serverID, hexEncodedPublicKey, displayName, body, timestamp, publicChatMessageType, quote, attachments, profilePicture, signature)
                        if (groupMessage.hasValidSignature()) groupMessage else null
                    } catch (exception: Exception) {
                        Log.d("Loki", "Couldn't parse message for open group with ID: $channel on server: $server from: ${JsonUtil.toJson(message)}. Exception: ${exception.message}")
                        return@mapNotNull null
                    }
                }.sortedBy { it.timestamp }
                messages
            } catch (exception: Exception) {
                Log.d("Loki", "Couldn't parse messages for open group with ID: $channel on server: $server.")
                throw exception
            }
        }
    }

    public fun getDeletedMessageServerIDs(channel: Long, server: String): Promise<List<Long>, Exception> {
        Log.d("Loki", "Getting deleted messages for open group with ID: $channel on server: $server.")
        val parameters = mutableMapOf<String, Any>()
        val lastDeletionServerID = apiDatabase.getLastDeletionServerID(channel, server)
        if (lastDeletionServerID != null) {
            parameters["since_id"] = lastDeletionServerID
        } else {
            parameters["count"] = fallbackBatchCount
        }
        return execute(HTTPVerb.GET, server, "loki/v1/channel/$channel/deletes", parameters = parameters).then(sharedContext) { response ->
            try {
                val bodyAsString = response.body!!
                val body = JsonUtil.fromJson(bodyAsString)
                val deletedMessageServerIDs = body.get("data").mapNotNull { deletion ->
                    try {
                        val serverID = deletion.get("id").asLong()
                        val messageServerID = deletion.get("message_id").asLong()
                        @Suppress("NAME_SHADOWING") val lastDeletionServerID = apiDatabase.getLastDeletionServerID(channel, server)
                        if (serverID > (lastDeletionServerID ?: 0)) { apiDatabase.setLastDeletionServerID(channel, server, serverID) }
                        messageServerID
                    } catch (exception: Exception) {
                        Log.d("Loki", "Couldn't parse deleted message for open group with ID: $channel on server: $server. Exception: ${exception.message}")
                        return@mapNotNull null
                    }
                }
                deletedMessageServerIDs
            } catch (exception: Exception) {
                Log.d("Loki", "Couldn't parse deleted messages for open group with ID: $channel on server: $server.")
                throw exception
            }
        }
    }

    public fun sendMessage(message: LokiPublicChatMessage, channel: Long, server: String): Promise<LokiPublicChatMessage, Exception> {
        val deferred = deferred<LokiPublicChatMessage, Exception>()
        Thread {
            val signedMessage = message.sign(userPrivateKey)
            if (signedMessage == null) {
                deferred.reject(SnodeAPI.Error.MessageSigningFailed)
            } else {
                retryIfNeeded(maxRetryCount) {
                    Log.d("Loki", "Sending message to open group with ID: $channel on server: $server.")
                    val parameters = signedMessage.toJSON()
                    execute(HTTPVerb.POST, server, "channels/$channel/messages", parameters = parameters).then(sharedContext) { response ->
                        try {
                            val bodyAsString = response.body!!
                            val body = JsonUtil.fromJson(bodyAsString)
                            val data = body.get("data")
                            val serverID = data.get("id").asLong()
                            val displayName = userDatabase.getDisplayName(userPublicKey) ?: "Anonymous"
                            val text = data.get("text").asText()
                            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                            val dateAsString = data.get("created_at").asText()
                            val timestamp = format.parse(dateAsString).time
                            @Suppress("NAME_SHADOWING") val message = LokiPublicChatMessage(serverID, userPublicKey, displayName, text, timestamp, publicChatMessageType, message.quote, message.attachments, null, signedMessage.signature)
                            message
                        } catch (exception: Exception) {
                            Log.d("Loki", "Couldn't parse message for open group with ID: $channel on server: $server.")
                            throw exception
                        }
                    }
                }.success {
                    deferred.resolve(it)
                }.fail {
                    deferred.reject(it)
                }
            }
        }.start()
        return deferred.promise
    }

    public fun deleteMessage(messageServerID: Long, channel: Long, server: String, isSentByUser: Boolean): Promise<Long, Exception> {
        return retryIfNeeded(maxRetryCount) {
            val isModerationRequest = !isSentByUser
            Log.d("Loki", "Deleting message with ID: $messageServerID from open group with ID: $channel on server: $server (isModerationRequest = $isModerationRequest).")
            val endpoint = if (isSentByUser) "channels/$channel/messages/$messageServerID" else "loki/v1/moderation/message/$messageServerID"
            execute(HTTPVerb.DELETE, server, endpoint).then {
                Log.d("Loki", "Deleted message with ID: $messageServerID from open group with ID: $channel on server: $server.")
                messageServerID
            }
        }
    }

    public fun deleteMessages(messageServerIDs: List<Long>, channel: Long, server: String, isSentByUser: Boolean): Promise<List<Long>, Exception> {
        return retryIfNeeded(maxRetryCount) {
            val isModerationRequest = !isSentByUser
            val parameters = mapOf( "ids" to messageServerIDs.joinToString(",") )
            Log.d("Loki", "Deleting messages with IDs: ${messageServerIDs.joinToString()} from open group with ID: $channel on server: $server (isModerationRequest = $isModerationRequest).")
            val endpoint = if (isSentByUser) "loki/v1/messages" else "loki/v1/moderation/messages"
            execute(HTTPVerb.DELETE, server, endpoint, parameters = parameters).then {
                Log.d("Loki", "Deleted messages with IDs: $messageServerIDs from open group with ID: $channel on server: $server.")
                messageServerIDs
            }
        }
    }

    public fun getModerators(channel: Long, server: String): Promise<Set<String>, Exception> {
        return execute(HTTPVerb.GET, server, "loki/v1/channel/$channel/get_moderators").then(sharedContext) { response ->
            try {
                val bodyAsString = response.body!!
                @Suppress("NAME_SHADOWING") val body = JsonUtil.fromJson(bodyAsString, Map::class.java)
                @Suppress("UNCHECKED_CAST") val moderators = body["moderators"] as? List<String>
                val moderatorsAsSet = moderators.orEmpty().toSet()
                if (Companion.moderators[server] != null) {
                    Companion.moderators[server]!![channel] = moderatorsAsSet
                } else {
                    Companion.moderators[server] = hashMapOf( channel to moderatorsAsSet )
                }
                moderatorsAsSet
            } catch (exception: Exception) {
                Log.d("Loki", "Couldn't parse moderators for open group with ID: $channel on server: $server.")
                throw exception
            }
        }
    }

    public fun getChannelInfo(channel: Long, server: String): Promise<String, Exception> {
        return retryIfNeeded(maxRetryCount) {
            val parameters = mapOf( "include_annotations" to 1 )
            execute(HTTPVerb.GET, server, "/channels/$channel", parameters = parameters).then(sharedContext) { response ->
                try {
                    val bodyAsString = response.body!!
                    val body = JsonUtil.fromJson(bodyAsString)
                    val data = body.get("data")
                    val annotations = data.get("annotations")
                    val annotation = annotations.find { it.get("type").asText("") == channelInfoType } ?: throw SnodeAPI.Error.ParsingFailed
                    val info = annotation.get("value")
                    val displayName = info.get("name").asText()
                    val countInfo = data.get("counts")
                    val memberCount = countInfo.get("subscribers").asInt()
                    apiDatabase.setUserCount(memberCount, channel, server)
                    displayName
                } catch (exception: Exception) {
                    Log.d("Loki", "Couldn't parse info for open group with ID: $channel on server: $server.")
                    throw exception
                }
            }
        }
    }

    public fun join(channel: Long, server: String): Promise<Unit, Exception> {
        return retryIfNeeded(maxRetryCount) {
            execute(HTTPVerb.POST, server, "/channels/$channel/subscribe").then {
                Log.d("Loki", "Joined channel with ID: $channel on server: $server.")
            }
        }
    }

    public fun leave(channel: Long, server: String): Promise<Unit, Exception> {
        return retryIfNeeded(maxRetryCount) {
            execute(HTTPVerb.DELETE, server, "/channels/$channel/subscribe").then {
                Log.d("Loki", "Left channel with ID: $channel on server: $server.")
            }
        }
    }

    public fun getDisplayNames(hexEncodedPublicKeys: Set<String>, server: String): Promise<Map<String, String>, Exception> {
        return getUserProfiles(hexEncodedPublicKeys, server, false).map(sharedContext) { data ->
            val mapping = mutableMapOf<String, String>()
            for (user in data) {
                if (user.hasNonNull("username")) {
                    val hexEncodedPublicKey = user.get("username").asText()
                    val displayName = if (user.hasNonNull("name")) user.get("name").asText() else "Anonymous"
                    mapping[hexEncodedPublicKey] = displayName
                }
            }
            mapping
        }
    }

    public fun setDisplayName(newDisplayName: String?, server: String): Promise<Unit, Exception> {
        Log.d("Loki", "Updating display name on server: $server.")
        val parameters = mapOf( "name" to (newDisplayName ?: "") )
        return execute(HTTPVerb.PATCH, server, "users/me", parameters = parameters).map { Unit }
    }

    public fun setProfilePicture(server: String, profileKey: ByteArray, url: String?): Promise<Unit, Exception> {
        return setProfilePicture(server, Base64.encodeBytes(profileKey), url)
    }

    public fun setProfilePicture(server: String, profileKey: String, url: String?): Promise<Unit, Exception> {
        Log.d("Loki", "Updating profile picture on server: $server.")
        val value = when (url) {
            null -> null
            else -> mapOf( "profileKey" to profileKey, "url" to url )
        }
        // TODO: This may actually completely replace the annotations, have to double check it
        return setSelfAnnotation(server, profilePictureType, value).map { Unit }.fail {
            Log.d("Loki", "Failed to update profile picture due to error: $it.")
        }
    }
    // endregion
}
