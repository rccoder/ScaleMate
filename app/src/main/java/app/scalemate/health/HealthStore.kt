package app.scalemate.health

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.scalemate.data.Store
import app.scalemate.food.AiService
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
            createSyncTables(db)
        }
        fun createSyncTables(db:SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS health_records(recordId TEXT PRIMARY KEY,category TEXT NOT NULL,platformId TEXT NOT NULL DEFAULT '',start INTEGER NOT NULL,end INTEGER NOT NULL,payload TEXT NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS health_records_start ON health_records(start)")
            db.execSQL("CREATE INDEX IF NOT EXISTS health_records_category ON health_records(category,start)")
            db.execSQL("CREATE INDEX IF NOT EXISTS health_records_platform ON health_records(platformId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS health_sync_state(category TEXT PRIMARY KEY,token TEXT NOT NULL DEFAULT '',syncedAt INTEGER NOT NULL DEFAULT 0,permissionFingerprint TEXT NOT NULL DEFAULT '',rangeStart TEXT NOT NULL DEFAULT '',rangeEnd TEXT NOT NULL DEFAULT '',allHistory INTEGER NOT NULL DEFAULT 0)")
        }
    }
    fun setting(key:String)=store.readableDatabase.rawQuery("SELECT value FROM health_settings WHERE key=?",arrayOf(key)).use {if(it.moveToFirst())it.getString(0) else ""}
    fun setting(key:String,value:String){store.writableDatabase.insertWithOnConflict("health_settings",null,ContentValues().apply {put("key",key);put("value",value)},SQLiteDatabase.CONFLICT_REPLACE)}
    fun cacheRevision()=setting("sync:cacheRevision")
    fun markCacheChanged()=setting("sync:cacheRevision",healthId())
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
    fun activeSnapshot():HealthSnapshot?=setting("activeSnapshot").takeIf {it.isNotEmpty()}?.let(::snapshot)
    fun <T> transaction(block:()->T):T {
        val db=store.writableDatabase;db.beginTransaction()
        try {val result=block();db.setTransactionSuccessful();return result}finally {db.endTransaction()}
    }
    fun records(categories:Set<String>?=null,start:Long?=null,end:Long?=null):List<HealthRecord> {
        val clauses=mutableListOf<String>();val args=mutableListOf<String>()
        if(categories!=null) {
            if(categories.isEmpty())return emptyList()
            clauses+="category IN (${categories.joinToString(","){"?"}})"
            args+=categories
        }
        start?.let {clauses+="end>=?";args+=it.toString()}
        end?.let {clauses+="start<?";args+=it.toString()}
        val where=clauses.takeIf {it.isNotEmpty()}?.joinToString(" AND ").orEmpty()
        return store.readableDatabase.rawQuery("SELECT payload FROM health_records${if(where.isEmpty())"" else " WHERE $where"} ORDER BY start",args.toTypedArray()).use {c->buildList {while(c.moveToNext())add(HealthJson.cachedRecord(JSONObject(c.getString(0))))}}
    }
    suspend fun writeCachedRaw(records:List<HealthRecord>,writer:java.io.Writer) {
        // Payloads are already normalized JSON. Stream them without encoding every sample again.
        records.filterNot {it.id.startsWith("aggregate:")}.map {it.id}.distinct().chunked(400).forEach {ids->
            currentCoroutineContext().ensureActive()
            store.readableDatabase.rawQuery("SELECT payload FROM health_records WHERE recordId IN (${ids.joinToString(","){"?"}})",ids.toTypedArray()).use {cursor->
                while(cursor.moveToNext()) {
                    writer.write(cursor.getString(0));writer.write("\n")
                }
            }
        }
    }
    fun earliest(categories:Set<String>):Long? {
        if(categories.isEmpty())return null
        return store.readableDatabase.rawQuery("SELECT MIN(start) FROM health_records WHERE start>0 AND category IN (${categories.joinToString(","){"?"}})",categories.toTypedArray()).use {c->if(c.moveToFirst()&&!c.isNull(0))c.getLong(0) else null}
    }
    fun platformRecords(category:String,ids:List<String>):List<HealthRecord> = ids.distinct().chunked(400).flatMap {page->
        val placeholders=page.joinToString(","){"?"}
        store.readableDatabase.rawQuery("SELECT payload FROM health_records WHERE category=? AND platformId IN ($placeholders)",(listOf(category)+page).toTypedArray()).use {c->
            buildList {while(c.moveToNext())add(HealthJson.cachedRecord(JSONObject(c.getString(0))))}
        }
    }
    fun replaceRange(categories:Set<String>,start:Long,end:Long,records:List<HealthRecord>) {
        require(start<end)
        if(categories.isEmpty())return
        val db=store.writableDatabase;db.beginTransaction()
        try {
            val categoryArgs=categories.joinToString(","){"?"}
            // Raw records can overlap the range without starting inside it. Daily aggregates
            // are different: only the bucket whose own start falls in this range is stale.
            val args=(categories.toList()+listOf(start.toString(),end.toString())).toTypedArray()
            db.delete("health_records","category IN ($categoryArgs) AND recordId NOT LIKE 'aggregate:%' AND \"end\">=? AND \"start\"<?",args)
            db.delete("health_records","category IN ($categoryArgs) AND recordId LIKE 'aggregate:%' AND \"start\">=? AND \"start\"<?",args)
            records.forEach {r->
                db.insertWithOnConflict("health_records",null,ContentValues().apply {
                    put("recordId",r.id);put("category",r.kind);put("platformId",r.platformId);put("start",r.start);put("end",r.end);put("payload",HealthJson.cacheRecord(r).toString())
                },SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        }finally {db.endTransaction()}
    }
    fun replaceCategory(category:String,records:List<HealthRecord>)=transaction {
        require(records.all {it.kind==category})
        store.writableDatabase.delete("health_records","category=?",arrayOf(category))
        upsert(records)
    }
    fun replaceAggregates(category:String,start:Long,end:Long,records:List<HealthRecord>)=transaction {
        require(records.all {it.kind==category&&it.id.startsWith("aggregate:")})
        store.writableDatabase.delete("health_records","category=? AND recordId LIKE 'aggregate:%' AND start>=? AND start<?",arrayOf(category,start.toString(),end.toString()))
        upsert(records)
    }
    fun affectedDates(upserts:List<HealthRecord>,deletes:List<HealthRecord>,zone:java.time.ZoneId):Map<String,Set<String>> {
        val affected=linkedMapOf<String,MutableSet<String>>()
        (upserts+deletes).forEach {r->
            val dates=affected.getOrPut(r.kind){linkedSetOf()}
            dates+=healthRecordDates(r,zone)
            store.readableDatabase.rawQuery("SELECT payload FROM health_records WHERE category=? AND (recordId=? OR (platformId<>'' AND platformId=?))",arrayOf(r.kind,r.id,r.platformId)).use {c->
                while(c.moveToNext())dates+=healthRecordDates(HealthJson.cachedRecord(JSONObject(c.getString(0))),zone)
            }
        }
        return affected
    }
    fun upsert(records:List<HealthRecord>) {
        val db=store.writableDatabase;db.beginTransaction()
        try {
            records.forEach {r->
                db.insertWithOnConflict("health_records",null,ContentValues().apply {
                    put("recordId",r.id);put("category",r.kind);put("platformId",r.platformId);put("start",r.start);put("end",r.end);put("payload",HealthJson.cacheRecord(r).toString())
                },SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        }finally {db.endTransaction()}
    }
    fun applyIncremental(
        upserts:List<HealthRecord>,
        deletes:List<HealthRecord>,
        aggregateDates:Map<String,Set<String>>,
        zone:java.time.ZoneId
    ):Map<String,Set<String>> {
        if(upserts.isEmpty()&&deletes.isEmpty())return aggregateDates
        val db=store.writableDatabase;db.beginTransaction()
        val affected=aggregateDates.mapValues {it.value.toMutableSet()}.toMutableMap()
        affectedDates(upserts,deletes,zone).forEach {(category,dates)->affected.getOrPut(category){mutableSetOf()}+=dates}
        try {
            deletes.forEach {r->
                db.delete("health_records","category=? AND (recordId=? OR (platformId<>'' AND platformId=?))",arrayOf(r.kind,r.id,r.platformId))
            }
            upserts.forEach {r->
                db.insertWithOnConflict("health_records",null,ContentValues().apply {
                    put("recordId",r.id);put("category",r.kind);put("platformId",r.platformId);put("start",r.start);put("end",r.end);put("payload",HealthJson.cacheRecord(r).toString())
                },SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        }finally {db.endTransaction()}
        return affected
    }
    fun syncState(category:String):HealthSyncState {
        return store.readableDatabase.rawQuery("SELECT token,syncedAt,permissionFingerprint,rangeStart,rangeEnd,allHistory FROM health_sync_state WHERE category=?",arrayOf(category)).use {c->
            if(c.moveToFirst())HealthSyncState(category,c.getString(0),c.getLong(1),c.getString(2),c.getString(3),c.getString(4),c.getInt(5)!=0) else HealthSyncState(category)
        }
    }
    fun syncState(state:HealthSyncState) {
        store.writableDatabase.insertWithOnConflict("health_sync_state",null,ContentValues().apply {
            put("category",state.category);put("token",state.token);put("syncedAt",state.syncedAt)
            put("permissionFingerprint",state.permissionFingerprint);put("rangeStart",state.rangeStart)
            put("rangeEnd",state.rangeEnd);put("allHistory",if(state.allHistory)1 else 0)
        },SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun conversations():List<HealthConversation> = store.readableDatabase.rawQuery("SELECT payload FROM health_conversations ORDER BY updatedAt DESC",null).use {c->buildList {while(c.moveToNext())add(HealthJson.conversation(JSONObject(c.getString(0))))}}
    fun conversation(c:HealthConversation) {store.writableDatabase.insertWithOnConflict("health_conversations",null,ContentValues().apply {put("id",c.id);put("updatedAt",System.currentTimeMillis());put("payload",HealthJson.conversation(c).toString())},SQLiteDatabase.CONFLICT_REPLACE)}
    fun deleteConversation(id:String){store.writableDatabase.delete("health_conversations","id=?",arrayOf(id));prune()}
    fun reports():List<HealthReport> = store.readableDatabase.rawQuery("SELECT id,snapshotId,payload FROM health_reports ORDER BY createdAt DESC",null).use {c->buildList {while(c.moveToNext())add(HealthReport(c.getString(0),c.getString(1),HealthJson.message(JSONObject(c.getString(2)))))}}
    fun report(r:HealthReport){store.writableDatabase.insertOrThrow("health_reports",null,ContentValues().apply {put("id",r.id);put("snapshotId",r.snapshotId);put("createdAt",r.message.createdAt);put("payload",HealthJson.message(r.message).toString())})}
    fun deleteReport(id:String){store.writableDatabase.delete("health_reports","id=?",arrayOf(id));prune()}
    fun reminders():List<HealthReminder> = store.readableDatabase.rawQuery("SELECT payload FROM health_reminders ORDER BY createdAt DESC",null).use {c->buildList {while(c.moveToNext())add(HealthJson.reminder(JSONObject(c.getString(0))))}}
    fun reminder(r:HealthReminder):Boolean = store.writableDatabase.insertWithOnConflict("health_reminders",null,ContentValues().apply {put("id",r.id);put("eventKey",r.key);put("dayKey",java.time.Instant.ofEpochMilli(r.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString());put("createdAt",r.createdAt);put("payload",HealthJson.reminder(r).toString())},SQLiteDatabase.CONFLICT_IGNORE)!=-1L
    fun feedback(id:String,value:String) {val r=reminders().find {it.id==id} ?: return;store.writableDatabase.update("health_reminders",ContentValues().apply {put("payload",HealthJson.reminder(r.copy(feedback=value)).toString())},"id=?",arrayOf(id))}
    fun clear() {val db=store.writableDatabase;db.beginTransaction();try {listOf("health_snapshots","health_reports","health_conversations","health_reminders","health_records","health_sync_state").forEach {db.delete(it,null,null)};db.delete("health_settings","key LIKE 'consent:%' OR key LIKE 'sync:%' OR key='activeSnapshot'",null);db.setTransactionSuccessful()}finally {db.endTransaction()}}
    fun prune(now:Long=System.currentTimeMillis()) {
        val cutoff=now-90L*86400000
        store.writableDatabase.delete("health_reports","createdAt<?",arrayOf(cutoff.toString()))
        store.writableDatabase.delete("health_reminders","createdAt<?",arrayOf(cutoff.toString()))
        // A retained conversation/report must keep its evidence available.
        val pinned=(conversations().flatMap {it.messages.map {m->m.snapshotId}}+reports().map {it.snapshotId}+reminders().map {it.snapshotId}).toSet()
        store.readableDatabase.rawQuery("SELECT id FROM health_snapshots WHERE createdAt<?",arrayOf(cutoff.toString())).use {c->while(c.moveToNext()){val id=c.getString(0);if(id !in pinned)store.writableDatabase.delete("health_snapshots","id=?",arrayOf(id))}}
    }
}
