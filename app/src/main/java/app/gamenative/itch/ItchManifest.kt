package app.gamenative.itch

/** One launch action declared by a game's `.itch.toml` manifest. */
data class ItchManifestAction(
    val name: String,
    val path: String,
    val args: List<String> = emptyList(),
    val platform: String? = null,
    val scope: String? = null,
)

/** A launch target resolved from a manifest or discovered in the game folder. */
data class ItchLaunchSpec(
    val target: String,
    val args: List<String> = emptyList(),
    val fromManifest: Boolean = false,
    val actionName: String? = null,
)

/**
 * Minimal, dependency-free reader for the subset of `.itch.toml` that matters
 * for launching a Windows build: `[[actions]]` tables with `name`, `path`,
 * `args`, `platform` and `scope`.
 *
 * Only string values and arrays of strings are supported, which is all the
 * itch.io manifest format uses for actions. Unknown tables (`prereqs`,
 * `actions.locales.*`, …) are ignored on purpose.
 */
object ItchManifest {
    private const val FILE_NAME = ".itch.toml"

    fun fileName(): String = FILE_NAME

    fun parse(text: String): List<ItchManifestAction> {
        val actions = mutableListOf<ItchManifestAction>()
        var draft: MutableMap<String, Any>? = null

        fun flush() {
            val fields = draft ?: return
            draft = null
            val name = (fields["name"] as? String)?.trim().orEmpty()
            val path = (fields["path"] as? String)?.trim().orEmpty()
            if (name.isEmpty() || path.isEmpty()) return
            actions += ItchManifestAction(
                name = name,
                path = path,
                args = (fields["args"] as? List<*>)?.filterIsInstance<String>().orEmpty(),
                platform = (fields["platform"] as? String)?.trim()?.takeIf(String::isNotEmpty),
                scope = (fields["scope"] as? String)?.trim()?.takeIf(String::isNotEmpty),
            )
        }

        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var index = 0
        while (index < lines.size) {
            val line = stripComment(lines[index]).trim()
            index += 1
            if (line.isEmpty()) continue

            if (line.startsWith("[[") && line.endsWith("]]")) {
                flush()
                val header = line.removePrefix("[[").removeSuffix("]]").trim()
                if (header == "actions") draft = mutableMapOf()
                continue
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                flush()
                continue
            }

            val fields = draft ?: continue
            val equals = line.indexOf('=')
            if (equals <= 0) continue
            val key = line.substring(0, equals).trim()
            var value = line.substring(equals + 1).trim()
            if (value.startsWith("[") && !isBalancedArray(value)) {
                val builder = StringBuilder(value)
                while (index < lines.size) {
                    val continuation = stripComment(lines[index]).trim()
                    index += 1
                    if (continuation.isEmpty()) continue
                    builder.append(' ').append(continuation)
                    if (isBalancedArray(builder.toString())) break
                }
                value = builder.toString()
            }

            when (key) {
                "name" -> parseString(value)?.let { fields["name"] = it }
                "path" -> parseString(value)?.let { fields["path"] = it }
                "platform" -> parseString(value)?.let { fields["platform"] = it }
                "scope" -> parseString(value)?.let { fields["scope"] = it }
                "args" -> parseArray(value)?.let { fields["args"] = it }
            }
        }
        flush()
        return actions
    }

    /**
     * Picks the Windows launch action: `platform` may be absent or "windows",
     * and the path must be a local executable rather than a URL. A `play` named
     * action wins over editor/manual actions, matching the itch app.
     */
    fun pickWindowsAction(actions: List<ItchManifestAction>): ItchManifestAction? {
        val windows = actions.filter { action ->
            val platform = action.platform?.lowercase()
            (platform == null || platform == "windows") &&
                !action.path.contains("://") &&
                action.path.lowercase().endsWith(".exe")
        }
        if (windows.isEmpty()) return null
        return windows.firstOrNull { it.name.equals("play", ignoreCase = true) }
            ?: windows.firstOrNull { !isUtilityAction(it) }
            ?: windows.first()
    }

    /**
     * Resolves the action against an on-disk game folder. Returns null when the
     * manifest is missing, unparsable or points at a file that isn't there.
     */
    fun resolveLaunch(gameRoot: java.io.File): ItchLaunchSpec? {
        val manifest = java.io.File(gameRoot, FILE_NAME)
        if (!manifest.isFile) return null
        val text = runCatching { manifest.readText() }.getOrNull() ?: return null
        val action = pickWindowsAction(parse(text)) ?: return null
        val relative = action.path.replace('\\', '/').trimStart('/')
        if (relative.contains("..")) return null
        val target = java.io.File(gameRoot, relative)
        if (!target.isFile) return null
        return ItchLaunchSpec(
            target = target.relativeTo(gameRoot).invariantSeparatorsPath,
            args = action.args,
            fromManifest = true,
            actionName = action.name,
        )
    }

    private fun isUtilityAction(action: ItchManifestAction): Boolean {
        val haystack = "${action.name} ${action.path}".lowercase()
        return listOf("editor", "manual", "guide", "artbook", "soundtrack").any(haystack::contains)
    }

    private fun stripComment(line: String): String {
        var inDouble = false
        var inSingle = false
        var escaped = false
        for (position in line.indices) {
            val char = line[position]
            if (escaped) {
                escaped = false
                continue
            }
            when {
                char == '\\' && inDouble -> escaped = true
                char == '"' && !inSingle -> inDouble = !inDouble
                char == '\'' && !inDouble -> inSingle = !inSingle
                char == '#' && !inDouble && !inSingle -> return line.substring(0, position)
            }
        }
        return line
    }

    private fun parseString(raw: String): String? {
        val value = raw.trim()
        if (value.length < 2) return null
        return when {
            value.startsWith("\"") && value.endsWith("\"") -> unescape(value.substring(1, value.length - 1))
            value.startsWith("'") && value.endsWith("'") -> value.substring(1, value.length - 1)
            else -> null
        }
    }

    private fun unescape(value: String): String {
        val builder = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\\' && index + 1 < value.length) {
                when (val next = value[index + 1]) {
                    '"', '\\' -> builder.append(next)
                    'n' -> builder.append('\n')
                    't' -> builder.append('\t')
                    'r' -> builder.append('\r')
                    else -> builder.append(char).append(next)
                }
                index += 2
            } else {
                builder.append(char)
                index += 1
            }
        }
        return builder.toString()
    }

    private fun parseArray(raw: String): List<String>? {
        val value = raw.trim()
        if (!value.startsWith("[") || !value.endsWith("]")) return null
        val body = value.substring(1, value.length - 1)
        if (body.isBlank()) return emptyList()
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inDouble = false
        var inSingle = false
        var escaped = false
        for (char in body) {
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                }
                char == '\\' && inDouble -> {
                    current.append(char)
                    escaped = true
                }
                char == '"' && !inSingle -> {
                    inDouble = !inDouble
                    current.append(char)
                }
                char == '\'' && !inDouble -> {
                    inSingle = !inSingle
                    current.append(char)
                }
                char == ',' && !inDouble && !inSingle -> {
                    parts += current.toString().trim()
                    current.clear()
                }
                else -> current.append(char)
            }
        }
        if (current.isNotBlank()) parts += current.toString().trim()
        return parts.mapNotNull(::parseString)
    }

    private fun isBalancedArray(raw: String): Boolean {
        var depth = 0
        var inDouble = false
        var inSingle = false
        var escaped = false
        for (char in raw) {
            when {
                escaped -> escaped = false
                char == '\\' && inDouble -> escaped = true
                char == '"' && !inSingle -> inDouble = !inDouble
                char == '\'' && !inDouble -> inSingle = !inSingle
                char == '[' && !inDouble && !inSingle -> depth += 1
                char == ']' && !inDouble && !inSingle -> depth -= 1
            }
        }
        return depth <= 0
    }
}
