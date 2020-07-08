package org.whispersystems.signalservice.loki.api.shelved.rssfeeds

import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.functional.map
import okhttp3.Request
import org.whispersystems.signalservice.internal.util.JsonUtil
import org.whispersystems.signalservice.loki.api.SnodeAPI
import org.whispersystems.signalservice.loki.api.deprecated.LokiHTTPClient
import org.whispersystems.signalservice.loki.api.deprecated.LokiFileServerProxy

object LokiRSSFeedProxy {

    fun fetch(url: String): Promise<String, Exception> {
        var client = LokiHTTPClient(60)
        val builder = Request.Builder().url(url).get()
        val feeds = mapOf( "messenger-updates/feed" to "loki/v1/rss/messenger", "loki.network/feed" to "loki/v1/rss/loki" )
        for (feed in feeds) {
            if (url.toLowerCase().contains(feed.key)) {
                val fileServer = "https://file.getsession.org"
                builder.url("$fileServer/${feed.value}")
                client = LokiFileServerProxy(fileServer)
                break
            }
        }
        return client.execute(builder.build()).map { response ->
            if (!response.isSuccess) {
                throw SnodeAPI.Error.HTTPRequestFailed(response.statusCode)
            }
            val body = response.body ?: throw SnodeAPI.Error.ResponseBodyMissing
            val json = JsonUtil.fromJson(body)
            json.get("data").asText()
        }
    }
}
