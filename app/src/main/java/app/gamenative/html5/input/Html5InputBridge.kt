package app.gamenative.html5.input

import android.webkit.JavascriptInterface
import java.util.concurrent.ConcurrentLinkedQueue

// main thread enqueues event-spec JSON; the JS binder thread drains once per rAF tick.
class Html5InputBridge {
    private val queue = ConcurrentLinkedQueue<String>()

    fun enqueue(eventSpecJson: String) {
        queue.add(eventSpecJson)
    }

    @JavascriptInterface
    fun drainQueue(): String {
        val specs = buildList {
            var s = queue.poll()
            while (s != null) {
                add(s)
                s = queue.poll()
            }
        }
        return if (specs.isEmpty()) "[]" else "[${specs.joinToString(",")}]"
    }
}
