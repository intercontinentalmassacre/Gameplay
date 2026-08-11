package app.gamenative.localgames

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

class InstallationSessionStore(context: Context) {
    private val directory = File(context.filesDir, DIRECTORY_NAME)

    @Synchronized
    fun save(session: InstallationSession) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create installation session directory")
        }

        val destination = sessionFile(session.id)
        val temporary = File(directory, "${session.id}.json.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(session.toJson().toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw IOException("Could not persist installation session ${session.id}")
        }
    }

    @Synchronized
    fun load(id: String): InstallationSession? {
        val file = sessionFile(id)
        if (!file.isFile) return null
        return runCatching { sessionFromJson(JSONObject(file.readText(Charsets.UTF_8))) }.getOrNull()
    }

    @Synchronized
    fun loadAll(): List<InstallationSession> {
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .mapNotNull { file ->
                runCatching { sessionFromJson(JSONObject(file.readText(Charsets.UTF_8))) }.getOrNull()
            }
            .sortedByDescending(InstallationSession::updatedAt)
    }

    private fun sessionFile(id: String): File {
        require(SAFE_SESSION_ID.matches(id)) { "Invalid installation session id" }
        return File(directory, "$id.json")
    }

    private fun InstallationSession.toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("id", id)
        put("title", title)
        put("sourceUri", sourceUri)
        put("sourceName", sourceName)
        put("installerType", installerType.name)
        put("managedInstallerPath", managedInstallerPath)
        put("installerRelativePath", installerRelativePath)
        put("state", state.name)
        put("previousState", previousState?.name)
        put("appId", appId)
        put("containerId", containerId)
        put("selectedExecutablePath", selectedExecutablePath)
        put("candidateExecutablePaths", JSONArray(candidateExecutablePaths))
        put("baselineExecutablePaths", JSONArray(baselineExecutablePaths))
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("lastError", lastError)
    }

    private fun sessionFromJson(json: JSONObject): InstallationSession {
        require(json.optInt("schemaVersion") in 1..SCHEMA_VERSION) { "Unsupported session schema" }
        return InstallationSession(
            id = json.getString("id"),
            title = json.getString("title"),
            sourceUri = json.getString("sourceUri"),
            sourceName = json.getString("sourceName"),
            installerType = InstallerType.valueOf(json.getString("installerType")),
            managedInstallerPath = json.getString("managedInstallerPath"),
            installerRelativePath = json.getString("installerRelativePath"),
            state = InstallationState.valueOf(json.getString("state")),
            previousState = json.optNullableString("previousState")?.let(InstallationState::valueOf),
            appId = json.optNullableString("appId"),
            containerId = json.optNullableString("containerId"),
            selectedExecutablePath = json.optNullableString("selectedExecutablePath"),
            candidateExecutablePaths = json.optJSONArray("candidateExecutablePaths")?.let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        array.optString(index).takeIf(String::isNotBlank)?.let(::add)
                    }
                }
            }.orEmpty(),
            baselineExecutablePaths = json.optJSONArray("baselineExecutablePaths")?.let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        array.optString(index).takeIf(String::isNotBlank)?.let(::add)
                    }
                }
            }.orEmpty(),
            createdAt = json.getLong("createdAt"),
            updatedAt = json.getLong("updatedAt"),
            lastError = json.optNullableString("lastError"),
        )
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf(String::isNotBlank)

    private companion object {
        const val DIRECTORY_NAME = "local-installations"
        const val SCHEMA_VERSION = 2
        val SAFE_SESSION_ID = Regex("[A-Za-z0-9-]{1,80}")
    }
}
