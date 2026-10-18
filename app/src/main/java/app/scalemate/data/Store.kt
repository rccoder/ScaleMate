package app.scalemate.data

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class BoundScale(val id: String, val name: String, val address: String, val verified: Boolean)
data class Measurement(val id: String, val deviceId: String, val source: String, val kg: Double, val time: Long, val offset: Int, val status: String = "pending", val platformId: String = "", val attempts: Int = 0)

/** Durable outbox and records share one row, so saving the measurement also saves its write task. */
class Store(context: Context, name: String = "scalemate.db") : SQLiteOpenHelper(context, name, null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE scales(id TEXT PRIMARY KEY,name TEXT NOT NULL,address TEXT UNIQUE NOT NULL,verified INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE measurements(id TEXT PRIMARY KEY,deviceId TEXT NOT NULL,source TEXT NOT NULL,kg REAL NOT NULL,time INTEGER NOT NULL,offset INTEGER NOT NULL,status TEXT NOT NULL,platformId TEXT NOT NULL,attempts INTEGER NOT NULL)")
        createFoodTables(db)
        createLibraryTables(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion<2) createFoodTables(db)
        else if(oldVersion<3) db.execSQL("ALTER TABLE ai_services ADD COLUMN format TEXT NOT NULL DEFAULT 'openai'")
        if(oldVersion<4)createLibraryTables(db)
    }
    private fun createLibraryTables(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS food_library(id TEXT PRIMARY KEY,nameKey TEXT NOT NULL UNIQUE,payload TEXT NOT NULL,favorite INTEGER NOT NULL DEFAULT 0,useCount INTEGER NOT NULL DEFAULT 0,lastUsed INTEGER NOT NULL DEFAULT 0)")
    }
    private fun createFoodTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE food_meals(id TEXT PRIMARY KEY,revision INTEGER NOT NULL,time INTEGER NOT NULL,payload TEXT NOT NULL,status TEXT NOT NULL,platformId TEXT NOT NULL DEFAULT '',attempts INTEGER NOT NULL DEFAULT 0,deleted INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX food_meals_time ON food_meals(time)")
        db.execSQL("CREATE TABLE food_templates(id TEXT PRIMARY KEY,payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE ai_services(id TEXT PRIMARY KEY,name TEXT NOT NULL,url TEXT NOT NULL,model TEXT NOT NULL,secret TEXT NOT NULL,images INTEGER NOT NULL,version INTEGER NOT NULL,consent INTEGER NOT NULL,format TEXT NOT NULL DEFAULT 'openai')")
        db.execSQL("CREATE TABLE food_settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
    }
    fun scales(): List<BoundScale> = readableDatabase.rawQuery("SELECT id,name,address,verified FROM scales ORDER BY rowid", null).use { c -> buildList { while(c.moveToNext()) add(BoundScale(c.getString(0), c.getString(1), c.getString(2), c.getInt(3) != 0)) } }
    fun bind(s: BoundScale) { writableDatabase.insertWithOnConflict("scales", null, ContentValues().apply { put("id",s.id);put("name",s.name);put("address",s.address);put("verified",if(s.verified)1 else 0) }, SQLiteDatabase.CONFLICT_IGNORE) }
    fun rename(id: String, name: String) { require(name.isNotBlank() && name.length <= 60); writableDatabase.update("scales", ContentValues().apply { put("name", name.trim()) }, "id=?", arrayOf(id)) }
    fun verify(id: String) { writableDatabase.update("scales", ContentValues().apply { put("verified",1) }, "id=?", arrayOf(id)) }
    fun rebind(id: String, address: String) { writableDatabase.update("scales", ContentValues().apply {put("address",address);put("verified",0)},"id=?",arrayOf(id)) }
    fun unbind(id: String) { writableDatabase.delete("scales", "id=?", arrayOf(id)) }
    fun save(m: Measurement) {
        val values = ContentValues().apply { put("id",m.id);put("deviceId",m.deviceId);put("source",m.source);put("kg",m.kg);put("time",m.time);put("offset",m.offset);put("status",m.status);put("platformId",m.platformId);put("attempts",m.attempts) }
        // Duplicate session commits are harmless; storage errors still throw.
        val db = writableDatabase; db.beginTransaction()
        try { db.insertWithOnConflict("measurements", null, values, SQLiteDatabase.CONFLICT_IGNORE);db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    fun records(): List<Measurement> = readableDatabase.rawQuery("SELECT id,deviceId,source,kg,time,offset,status,platformId,attempts FROM measurements ORDER BY time DESC",null).use { c -> buildList { while(c.moveToNext()) add(Measurement(c.getString(0),c.getString(1),c.getString(2),c.getDouble(3),c.getLong(4),c.getInt(5),c.getString(6),c.getString(7),c.getInt(8))) } }
    fun status(id: String, status: String, platformId: String? = null, increment: Boolean = false) {
        writableDatabase.update("measurements",ContentValues().apply {put("status",status);if(platformId!=null)put("platformId",platformId)},"id=?",arrayOf(id))
        if(increment) writableDatabase.execSQL("UPDATE measurements SET attempts=attempts+1 WHERE id=?", arrayOf(id))
    }
    fun deleteLocal(id: String) { writableDatabase.delete("measurements","id=?",arrayOf(id)) }
}
