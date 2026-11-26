package app.gamenative.db

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.ItchGame
import app.gamenative.di.DatabaseModule
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V25ToV26MigrationTest {
    @Test
    fun `opening a version 25 database adds the itch games table and keeps data`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val schemaPath = "schemas/app.gamenative.db.PluviaDatabase/25.json"
        val schemaFile = listOf(File(schemaPath), File("app", schemaPath)).first { it.isFile }
        val schema = JSONObject(schemaFile.readText()).getJSONObject("database")
        val dbFile = context.getDatabasePath(DATABASE_NAME)
        dbFile.parentFile?.mkdirs()

        try {
            SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (j in 0 until indices.length()) {
                        db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                db.execSQL("INSERT INTO app_change_numbers (appId, changeNumber) VALUES (42, 321)")
                db.version = 25
            }

            val database = DatabaseModule().provideDatabase(context)
            try {
                // Old data survives the migration.
                database.openHelper.writableDatabase.query(
                    "SELECT changeNumber FROM app_change_numbers WHERE appId = 42",
                ).use { row ->
                    assertTrue(row.moveToFirst())
                    assertEquals(321, row.getInt(0))
                }

                // The new table exists and is usable through the DAO.
                val dao = database.itchGameDao()
                dao.insertAll(
                    listOf(
                        ItchGame(
                            gameId = 341190,
                            title = "Pikuniku",
                            coverUrl = "https://img.itch.zone/pikuniku.jpg",
                            accountId = 7,
                            isOwned = true,
                        ),
                    ),
                )
                val stored = dao.getByGameId(341190)
                assertEquals("Pikuniku", stored?.title)
                assertEquals("ITCH_341190", stored?.appId)
                assertEquals(false, stored?.isInstalled)
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(DATABASE_NAME)
        }
    }
}
