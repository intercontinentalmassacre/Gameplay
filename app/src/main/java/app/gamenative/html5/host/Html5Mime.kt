package app.gamenative.html5.host

// Adapted from GameNative 774a77f4f.
internal fun mimeFor(name: String): String = when {
    name.endsWith(".html", ignoreCase = true) -> "text/html"
    name.endsWith(".htm", ignoreCase = true) -> "text/html"
    name.endsWith(".js", ignoreCase = true) || name.endsWith(".mjs", ignoreCase = true) -> "application/javascript"
    name.endsWith(".css", ignoreCase = true) -> "text/css"
    name.endsWith(".json", ignoreCase = true) -> "application/json"
    name.endsWith(".png", ignoreCase = true) -> "image/png"
    name.endsWith(".jpg", ignoreCase = true) -> "image/jpeg"
    name.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
    name.endsWith(".gif", ignoreCase = true) -> "image/gif"
    name.endsWith(".svg", ignoreCase = true) -> "image/svg+xml"
    name.endsWith(".webp", ignoreCase = true) -> "image/webp"
    name.endsWith(".ogg", ignoreCase = true) -> "audio/ogg"
    name.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
    name.endsWith(".wav", ignoreCase = true) -> "audio/wav"
    name.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
    name.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
    name.endsWith(".webm", ignoreCase = true) -> "video/webm"
    name.endsWith(".ogv", ignoreCase = true) -> "video/ogg"
    name.endsWith(".mov", ignoreCase = true) -> "video/quicktime"
    name.endsWith(".woff", ignoreCase = true) -> "font/woff"
    name.endsWith(".woff2", ignoreCase = true) -> "font/woff2"
    name.endsWith(".ttf", ignoreCase = true) -> "font/ttf"
    // WebAssembly.instantiateStreaming REQUIRES application/wasm -- octet-stream path throws.
    name.endsWith(".wasm", ignoreCase = true) -> "application/wasm"
    else -> "application/octet-stream"
}
