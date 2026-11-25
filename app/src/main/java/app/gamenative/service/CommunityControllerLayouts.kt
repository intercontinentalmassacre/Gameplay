package app.gamenative.service

import app.gamenative.utils.Net
import `in`.dragonbra.javasteam.networking.steam3.ProtocolTypes
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration
import java.util.EnumSet
import kotlinx.coroutines.withTimeoutOrNull
import `in`.dragonbra.javasteam.enums.EPublishedFileQueryType
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_QueryFiles_Request
import `in`.dragonbra.javasteam.rpc.service.PublishedFile
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException

internal object CommunityControllerLayouts {
    const val PAGE_SIZE = 20
    private const val MAX_LAYOUT_BYTES = 2L * 1024 * 1024

    data class Layout(val id: Long, val title: String, val device: String, val url: String)
    data class Page(val layouts: List<Layout>, val total: Int)

    fun request(appId: Int, query: String, page: Int): CPublishedFile_QueryFiles_Request {
        require(appId > 0 && page > 0)
        fun tag(key: String, value: String) = CPublishedFile_QueryFiles_Request.KVTag.newBuilder()
            .setKey(key).setValue(value)
        return CPublishedFile_QueryFiles_Request.newBuilder()
            .setAppid(241100) // Steam Input Configs is the Workshop consumer for all games.
            .setFiletype(15) // Controller bindings in the QueryFiles matching-filetype enum.
            .setQueryType(if (query.isBlank()) EPublishedFileQueryType.RankedByVote.code() else EPublishedFileQueryType.RankedByTextSearch.code())
            .setPage(page).setNumperpage(PAGE_SIZE).setSearchText(query.trim())
            .setReturnDetails(true).setReturnTags(true)
            .addRequiredKvTags(tag("app", appId.toString()))
            .addRequiredKvTags(tag("visibility", "public"))
            .addRequiredKvTags(tag("deleted", "0"))
            .build()
    }

    suspend fun search(appId: Int, query: String, page: Int): Page = withContext(Dispatchers.IO) {
        val queryRequest = request(appId, query, page)
        val signedIn = SteamService.instance?.steamClient?.takeIf { SteamService.isLoggedIn }
        if (signedIn != null) return@withContext queryFiles(signedIn, queryRequest)
        // Public layouts are also available while playing without a signed-in Steam session.
        val guest = SteamClient(SteamConfiguration.create {
            it.withProtocolTypes(EnumSet.of(ProtocolTypes.WEB_SOCKET))
            it.withConnectionTimeout(10_000L)
        })
        try {
            withTimeoutOrNull(25_000L) {
                guest.connect()
                awaitGuestLogin(guest)
                queryFiles(guest, queryRequest)
            } ?: throw IOException("Steam connection timed out")
        } finally {
            guest.disconnect()
        }
    }

    private suspend fun awaitGuestLogin(guest: SteamClient) {
        while (true) {
            when (val callback = guest.waitForCallbackAsync()) {
                is ConnectedCallback -> guest.getHandler<SteamUser>()!!.logOnAnonymous()
                is DisconnectedCallback -> throw IOException("Steam disconnected")
                is LoggedOnCallback -> {
                    if (callback.result != EResult.OK) throw IOException("Steam guest login failed")
                    return
                }
            }
        }
    }

    private suspend fun queryFiles(client: SteamClient, request: CPublishedFile_QueryFiles_Request): Page {
        val service = client.getHandler<SteamUnifiedMessages>()!!.createService<PublishedFile>()
        val job = service.queryFiles(request)
        job.timeout = 15_000L
        val response = job.toFuture().await()
        if (response.result != EResult.OK) throw IOException("Steam query failed: ${response.result}")
        val body = response.body
        return Page(body.publishedfiledetailsList.map { file ->
            val controller = file.tagsList.firstOrNull { it.tag.startsWith("controller_") && it.tag != "controller_native" }?.tag.orEmpty()
            val deviceName = when (controller) {
                "controller_xbox360" -> "Xbox 360"
                "controller_xboxone" -> "Xbox One"
                "controller_neptune" -> "Steam Deck"
                "controller_steamcontroller_gordon" -> "Steam Controller"
                "controller_ps4" -> "DualShock 4"
                "controller_ps5" -> "DualSense"
                "controller_switch_pro" -> "Switch Pro"
                else -> controller.removePrefix("controller_").replace('_', ' ')
            }
            Layout(file.publishedfileid, file.title, deviceName, file.fileUrl)
        }, body.total)
    }

    suspend fun download(layout: Layout): String = withContext(Dispatchers.IO) {
        val url = layout.url.toHttpUrlOrNull()
        if (url == null || !url.isHttps || !(url.host.endsWith(".steamusercontent.com") ||
                url.host.endsWith(".steamstatic.com") || url.host == "steamuserimages-a.akamaihd.net")) {
            throw IOException("Steam did not provide a downloadable layout")
        }
        Net.http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val body = response.body ?: throw IOException("Empty layout")
            if (!response.isSuccessful || body.source().request(MAX_LAYOUT_BYTES + 1)) throw IOException("Could not read layout")
            body.string().removePrefix("\uFEFF")
        }
    }
}
