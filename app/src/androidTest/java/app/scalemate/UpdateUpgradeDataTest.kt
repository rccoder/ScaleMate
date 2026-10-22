package app.scalemate

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.scalemate.update.UPDATE_PACKAGE_NAME
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in, read-only comparison; never logs records or credentials. */
@RunWith(AndroidJUnit4::class)
class UpdateUpgradeDataTest {
    @Test fun compareAcrossSignedUpdates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val mode = InstrumentationRegistry.getArguments().getString("upgradeSnapshotMode")
        val expectedPackage = InstrumentationRegistry.getArguments().getString("upgradeSnapshotPackage") ?: UPDATE_PACKAGE_NAME
        assumeTrue(expectedPackage in setOf(UPDATE_PACKAGE_NAME, "app.scalemate.fooddev") &&
            context.packageName == expectedPackage && mode in setOf("capture", "verify", "cleanup"))
        val baseline = File(context.filesDir, "upgrade-validation-digests.json")
        if (mode == "cleanup") {
            baseline.delete()
            return
        }
        val digests = JSONObject()
        val path = context.getDatabasePath("scalemate.db")
        assertTrue("Existing user database must be present", path.isFile)
        SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val tables = listOf("scales", "measurements", "food_meals", "food_templates",
                "food_library", "ai_services", "food_settings")
            for (table in tables) {
                val digest = MessageDigest.getInstance("SHA-256")
                db.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { cursor ->
                    while (cursor.moveToNext()) for (column in 0 until cursor.columnCount) {
                        val bytes = when (cursor.getType(column)) {
                            Cursor.FIELD_TYPE_NULL -> byteArrayOf()
                            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column)
                            else -> cursor.getString(column).toByteArray(Charsets.UTF_8)
                        }
                        digest.update("${cursor.getType(column)}:${bytes.size}:".toByteArray())
                        digest.update(bytes)
                    }
                }
                digests.put(table, digest.digest().joinToString("") { "%02x".format(it) })
            }
        }
        val prefs = context.getSharedPreferences("settings", 0).all
            .filterKeys { !it.startsWith("update.") }.toSortedMap()
        val settings = JSONObject()
        prefs.forEach { (name, value) ->
            settings.put(name, if (value is Set<*>) value.map { it.toString() }.sorted().joinToString("\u0000") else value)
        }
        digests.put("settings", MessageDigest.getInstance("SHA-256")
            .digest(settings.toString().toByteArray()).joinToString("") { "%02x".format(it) })
        if (mode == "capture") {
            assertTrue("Do not overwrite an existing baseline", !baseline.exists())
            baseline.writeText(digests.toString())
        } else {
            assertTrue("Upgrade baseline must exist", baseline.isFile)
            val expected = JSONObject(baseline.readText())
            assertTrue("Application records, drafts, services or preferences changed",
                expected.keys().asSequence().toSet() == digests.keys().asSequence().toSet() && expected.keys().asSequence().all {
                    expected.getString(it) == digests.getString(it)
                })
        }
    }
}
