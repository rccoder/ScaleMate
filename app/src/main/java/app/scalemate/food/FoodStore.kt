package app.scalemate.food

import android.content.ContentValues
import app.scalemate.data.Store
import org.json.JSONObject

class FoodStore(private val store: Store) {
    fun meals(includeDeleted: Boolean=false): List<Meal> = store.readableDatabase.rawQuery("SELECT payload,status,platformId,attempts,deleted FROM food_meals ${if(includeDeleted)"" else "WHERE deleted=0"} ORDER BY time DESC",null).use {c->buildList {
        while(c.moveToNext()) add(FoodJson.meal(JSONObject(c.getString(0))).copy(status=c.getString(1),platformId=c.getString(2),attempts=c.getInt(3),deleted=c.getInt(4)!=0))
    }}
    fun save(m: Meal) = transaction {
        require(m.items.isNotEmpty() && m.items.all {it.valid()})
        store.writableDatabase.insertOrThrow("food_meals",null,ContentValues().apply {
            put("id",m.id);put("revision",m.revision);put("time",m.time);put("payload",FoodJson.meal(m.copy(items=m.items.map {it.copy(photos=emptyList())})).toString());put("status",m.status);put("platformId",m.platformId);put("attempts",m.attempts);put("deleted",if(m.deleted)1 else 0)
        })
        registerMeal(m)
    }
    fun update(m: Meal) {
        require(m.items.isNotEmpty() && m.items.all {it.valid()})
        check(store.writableDatabase.update("food_meals",ContentValues().apply {put("revision",m.revision);put("time",m.time);put("payload",FoodJson.meal(m.copy(items=m.items.map {it.copy(photos=emptyList())})).toString());put("status","pending");put("attempts",0)},"id=? AND deleted=0",arrayOf(m.id))==1)
    }
    fun status(id: String,status: String,platformId: String?=null,attempt: Boolean=false) {
        store.writableDatabase.update("food_meals",ContentValues().apply {put("status",status);if(platformId!=null)put("platformId",platformId)},"id=?",arrayOf(id))
        if(attempt)store.writableDatabase.execSQL("UPDATE food_meals SET attempts=attempts+1 WHERE id=?",arrayOf(id))
    }
    fun tombstone(id: String) {store.writableDatabase.execSQL("UPDATE food_meals SET deleted=1,status='deleting',attempts=0 WHERE id=?",arrayOf(id))}
    fun remove(id: String) {store.writableDatabase.delete("food_meals","id=?",arrayOf(id))}
    fun templates(): List<FoodTemplate> = store.readableDatabase.rawQuery("SELECT id,payload FROM food_templates ORDER BY rowid DESC",null).use {c->buildList {while(c.moveToNext())add(FoodTemplate(c.getString(0),FoodJson.item(JSONObject(c.getString(1)))))}}
    fun template(t: FoodTemplate) {store.writableDatabase.insertWithOnConflict("food_templates",null,ContentValues().apply {put("id",t.id);put("payload",FoodJson.item(t.item).toString())},android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)}
    fun removeTemplate(id: String) {store.writableDatabase.delete("food_templates","id=?",arrayOf(id))}
    fun library():List<FoodLibraryEntry> = store.readableDatabase.rawQuery("SELECT id,payload,favorite,useCount,lastUsed FROM food_library",null).use {c->
        FoodLibraryRules.sorted(buildList {while(c.moveToNext())add(FoodLibraryEntry(c.getString(0),FoodJson.item(JSONObject(c.getString(1))),c.getInt(2)!=0,c.getLong(3),c.getLong(4)))})
    }
    private fun writeLibrary(e:FoodLibraryEntry) {
        store.writableDatabase.insertOrThrow("food_library",null,ContentValues().apply {put("id",e.id);put("nameKey",FoodLibraryRules.nameKey(e.item.name));put("payload",FoodJson.item(e.item.copy(libraryId=e.id)).toString());put("favorite",if(e.favorite)1 else 0);put("useCount",e.useCount);put("lastUsed",e.lastUsed)})
    }
    private fun replaceLibrary(e:FoodLibraryEntry) {store.writableDatabase.delete("food_library","id=?",arrayOf(e.id));writeLibrary(e)}
    private fun pruneLibrary() {
        val keep=FoodLibraryRules.retained(library()).map {it.id}.toSet()
        library().filter {it.id !in keep}.forEach {store.writableDatabase.delete("food_library","id=?",arrayOf(it.id))}
    }
    private fun registerMeal(m:Meal,usedAt:Long=System.currentTimeMillis()) {
        m.items.groupBy {FoodLibraryRules.nameKey(it.name)}.values.forEach {group->
            val f=group.last().reuse();val old=library().find {FoodLibraryRules.nameKey(it.item.name)==FoodLibraryRules.nameKey(f.name)}
            replaceLibrary(if(old==null)FoodLibraryEntry(item=f,useCount=1,lastUsed=usedAt) else old.copy(item=if(old.favorite)old.item else f,useCount=old.useCount+1,lastUsed=maxOf(old.lastUsed,usedAt)))
        }
        pruneLibrary()
    }
    fun favorite(item:FoodItem,value:Boolean) = transaction {
        require(item.valid());val old=library().find {FoodLibraryRules.nameKey(it.item.name)==FoodLibraryRules.nameKey(item.name)}
        replaceLibrary(old?.copy(favorite=value,item=if(value&&!old.favorite)item.reuse() else old.item) ?: FoodLibraryEntry(item=item.reuse(),favorite=value,lastUsed=System.currentTimeMillis()))
        pruneLibrary()
    }
    fun editLibrary(id:String,item:FoodItem) = transaction {
        require(item.valid());val old=library().find {it.id==id} ?: error("Missing food")
        val conflict=library().find {it.id!=id&&FoodLibraryRules.nameKey(it.item.name)==FoodLibraryRules.nameKey(item.name)}
        require(conflict==null) // Avoid silently overwriting a different saved baseline on rename.
        replaceLibrary(old.copy(item=item.reuse()))
    }
    fun migrateLibrary() = transaction {
        if(setting("libraryMigrated")=="1")return@transaction
        meals().sortedBy {it.time}.forEach {registerMeal(it,it.time)}
        templates().forEach {template->
            val old=library().find {FoodLibraryRules.nameKey(it.item.name)==FoodLibraryRules.nameKey(template.item.name)}
            if(old?.favorite!=true)replaceLibrary(old?.copy(item=template.item.reuse(),favorite=true) ?: FoodLibraryEntry(item=template.item.reuse(),favorite=true))
        };pruneLibrary()
        setting("libraryMigrated","1")
    }
    fun setting(key: String): String = store.readableDatabase.rawQuery("SELECT value FROM food_settings WHERE key=?",arrayOf(key)).use {if(it.moveToFirst())it.getString(0) else ""}
    fun setting(key: String,value: String) {store.writableDatabase.insertWithOnConflict("food_settings",null,ContentValues().apply {put("key",key);put("value",value)},android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)}
    fun services(): List<AiService> = store.readableDatabase.rawQuery("SELECT id,name,url,model,secret,images,version,consent,format FROM ai_services ORDER BY rowid",null).use {c->buildList {while(c.moveToNext())add(AiService(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getInt(5)!=0,c.getLong(6),c.getLong(7),c.getString(8)))}}
    fun service(s: AiService) {store.writableDatabase.insertWithOnConflict("ai_services",null,ContentValues().apply {put("id",s.id);put("name",s.name);put("url",s.baseUrl);put("model",s.model);put("secret",s.encryptedKey);put("images",if(s.images)1 else 0);put("version",s.version);put("consent",s.consentVersion);put("format",s.format)},android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)}
    fun deleteService(id: String) {
        val db=store.writableDatabase;db.beginTransaction()
        try {db.delete("ai_services","id=?",arrayOf(id));listOf("vision","text").forEach {if(setting(it)==id)setting(it,"")};db.setTransactionSuccessful()} finally {db.endTransaction()}
    }
    fun <T> transaction(block: ()->T): T {val db=store.writableDatabase;db.beginTransaction();try {val result=block();db.setTransactionSuccessful();return result} finally {db.endTransaction()}}
}
