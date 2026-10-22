package app.scalemate.health

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.scalemate.data.Store
import app.scalemate.food.AiService
import org.json.JSONArray
import org.json.JSONObject

class HealthStore(private val store:Store) {
    companion object {
        fun createTables(db:SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS health_settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS health_snapshots(id TEXT PRIMARY KEY,createdAt INTEGER NOT NULL,payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS health_conversations(id TEXT PRIMARY KEY,updatedAt INTEGER NOT NULL,payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS health_reports(id TEXT PRIMARY KEY,snapshotId TEXT NOT NULL,createdAt INTEGER NOT NULL,payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS health_reminders(id TEXT PRIMARY KEY,eventKey TEXT NOT NULL UNIQUE,dayKey TEXT NOT NULL UNIQUE,createdAt INTEGER NOT NULL,payload TEXT NOT NULL)")
        }
    }
    fun setting(key:String)=store.readableDatabase.rawQuery("SELECT value FROM health_settings WHERE key=?",arrayOf(key)).use {if(it.moveToFirst())it.getString(0) else ""}
    fun setting(key:String,value:String){store.writableDatabase.insertWithOnConflict("health_settings",null,ContentValues().apply {put("key",key);put("value",value)},SQLiteDatabase.CONFLICT_REPLACE)}
    fun categories()=setting("categories").takeIf {it.isNotBlank()}?.let {HealthJson.strings(JSONArray(it)).toSet()} ?: emptySet()
    fun categories(ids:Set<String>)=setting("categories",JSONArray(ids.sorted()).toString())
    fun goals():HealthGoals {val o=setting("goals").takeIf {it.isNotBlank()}?.let(::JSONObject) ?: return HealthGoals();return HealthGoals(if(o.isNull("sleep"))null else o.getInt("sleep"),if(o.isNull("steps"))null else o.getLong("steps"))}
    fun goals(g:HealthGoals)=setting("goals",JSONObject().put("sleep",g.sleepMinutes ?: JSONObject.NULL).put("steps",g.steps ?: JSONObject.NULL).toString())
    fun reminderSettings()=setting("reminders").takeIf {it.isNotBlank()}?.let {HealthJson.reminders(JSONObject(it))} ?: HealthReminderSettings()
    fun reminderSettings(s:HealthReminderSettings)=setting("reminders",HealthJson.reminders(s).toString())
    fun sources():Map<String,String> {val o=setting("sources").takeIf {it.isNotBlank()}?.let(::JSONObject) ?: return emptyMap();return o.keys().asSequence().associateWith {o.getString(it)}}
    fun sources(map:Map<String,String>)=setting("sources",JSONObject(map).toString())
    fun consented(service:AiService,categories:Set<String>):Boolean {
        val accepted=setting("consent:${service.id}:${service.version}").takeIf {it.isNotBlank()} ?: return false
        return HealthJson.strings(JSONArray(accepted)).toSet().containsAll(categories)
    }
    fun consent(service:AiService,categories:Set<String>)=setting("consent:${service.id}:${service.version}",JSONArray(categories.sorted()).toString())
    fun snapshot(s:HealthSnapshot) {store.writableDatabase.insertWithOnConflict("health_snapshots",null,ContentValues().apply {put("id",s.id);put("createdAt",s.readAt);put("payload",HealthJson.snapshot(s).toString())},SQLiteDatabase.CONFLICT_REPLACE)}
    fun snapshot(id:String):HealthSnapshot?=store.readableDatabase.rawQuery("SELECT payload FROM health_snapshots WHERE id=?",arrayOf(id)).use {if(it.moveToFirst())HealthJson.snapshot(JSONObject(it.getString(0))) else null}
    fun latest():HealthSnapshot?=store.readableDatabase.rawQuery("SELECT payload FROM health_snapshots ORDER BY createdAt DESC LIMIT 1",null).use {if(it.moveToFirst())HealthJson.snapshot(JSONObject(it.getString(0))) else null}
    fun conversations():List<HealthConversation> = store.readableDatabase.rawQuery("SELECT payload FROM health_conversations ORDER BY updatedAt DESC",null).use {c->buildList {while(c.moveToNext())add(HealthJson.conversation(JSONObject(c.getString(0))))}}
    fun conversation(c:HealthConversation) {store.writableDatabase.insertWithOnConflict("health_conversations",null,ContentValues().apply {put("id",c.id);put("updatedAt",System.currentTimeMillis());put("payload",HealthJson.conversation(c).toString())},SQLiteDatabase.CONFLICT_REPLACE)}
    fun deleteConversation(id:String){store.writableDatabase.delete("health_conversations","id=?",arrayOf(id));prune()}
    fun reports():List<HealthReport> = store.readableDatabase.rawQuery("SELECT id,snapshotId,payload FROM health_reports ORDER BY createdAt DESC",null).use {c->buildList {while(c.moveToNext())add(HealthReport(c.getString(0),c.getString(1),HealthJson.message(JSONObject(c.getString(2)))))}}
    fun report(r:HealthReport){store.writableDatabase.insertOrThrow("health_reports",null,ContentValues().apply {put("id",r.id);put("snapshotId",r.snapshotId);put("createdAt",r.message.createdAt);put("payload",HealthJson.message(r.message).toString())})}
    fun deleteReport(id:String){store.writableDatabase.delete("health_reports","id=?",arrayOf(id));prune()}
    fun reminders():List<HealthReminder> = store.readableDatabase.rawQuery("SELECT payload FROM health_reminders ORDER BY createdAt DESC",null).use {c->buildList {while(c.moveToNext())add(HealthJson.reminder(JSONObject(c.getString(0))))}}
    fun reminder(r:HealthReminder):Boolean = store.writableDatabase.insertWithOnConflict("health_reminders",null,ContentValues().apply {put("id",r.id);put("eventKey",r.key);put("dayKey",java.time.Instant.ofEpochMilli(r.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString());put("createdAt",r.createdAt);put("payload",HealthJson.reminder(r).toString())},SQLiteDatabase.CONFLICT_IGNORE)!=-1L
    fun feedback(id:String,value:String) {val r=reminders().find {it.id==id} ?: return;store.writableDatabase.update("health_reminders",ContentValues().apply {put("payload",HealthJson.reminder(r.copy(feedback=value)).toString())},"id=?",arrayOf(id))}
    fun clear() {val db=store.writableDatabase;db.beginTransaction();try {listOf("health_snapshots","health_reports","health_conversations","health_reminders").forEach {db.delete(it,null,null)};db.delete("health_settings","key LIKE 'consent:%'",null);db.setTransactionSuccessful()}finally {db.endTransaction()}}
    fun prune(now:Long=System.currentTimeMillis()) {
        val cutoff=now-90L*86400000
        store.writableDatabase.delete("health_reports","createdAt<?",arrayOf(cutoff.toString()))
        store.writableDatabase.delete("health_reminders","createdAt<?",arrayOf(cutoff.toString()))
        // A retained conversation/report must keep its evidence available.
        val pinned=(conversations().flatMap {it.messages.map {m->m.snapshotId}}+reports().map {it.snapshotId}+reminders().map {it.snapshotId}).toSet()
        store.readableDatabase.rawQuery("SELECT id FROM health_snapshots WHERE createdAt<?",arrayOf(cutoff.toString())).use {c->while(c.moveToNext()){val id=c.getString(0);if(id !in pinned)store.writableDatabase.delete("health_snapshots","id=?",arrayOf(id))}}
    }
}
