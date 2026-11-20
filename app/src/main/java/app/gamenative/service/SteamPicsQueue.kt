package app.gamenative.service

import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** Coalesces PICS requests until their token lookup, queued work, and response processing finish. */
internal class SteamPicsQueue {
    private val pending = ConcurrentHashMap<Int, PICSRequest>()
    private val channel = Channel<List<PICSRequest>>(capacity = 1_000, onUndeliveredElement = ::release)

    val batches = channel.receiveAsFlow()

    suspend fun enqueue(
        requests: List<PICSRequest>,
        prepare: suspend (List<PICSRequest>) -> Unit = {},
    ): Int {
        val claimed = requests.filter { pending.putIfAbsent(it.id, it) == null }
        if (claimed.isEmpty()) return 0
        try {
            prepare(claimed)
            channel.send(claimed)
        } catch (error: Throwable) {
            release(claimed)
            throw error
        }
        return claimed.size
    }

    fun release(requests: List<PICSRequest>) {
        requests.forEach { pending.remove(it.id, it) }
    }

    fun discardQueued() {
        while (true) release(channel.tryReceive().getOrNull() ?: return)
    }
}
