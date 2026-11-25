package app.gamenative.db

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.gamenative.di.DatabaseModule
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V16UpgradeMigrationTest {
    @Test
    fun `opening a version 16 database preserves existing records`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val schemaPath = "schemas/app.gamenative.db.PluviaDatabase/16.json"
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
                db.execSQL("INSERT INTO app_change_numbers (appId, changeNumber) VALUES (42, 123)")
                db.version = 16
            }

            val database = DatabaseModule().provideDatabase(context)
            try {
                database.openHelper.writableDatabase.query(
                    "SELECT changeNumber FROM app_change_numbers WHERE appId = 42",
                ).use { row ->
                    assertTrue("Migration must retain the v16 record", row.moveToFirst())
                    assertEquals(123, row.getInt(0))
                }
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(DATABASE_NAME)
        }
    }
}
