package app.scalemate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.scalemate.data.Store
import app.scalemate.food.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiFormatTest {
    @Test fun anthropicSeparatesSystemAndConvertsInlineImage() {
        val messages=JSONArray("""[{"role":"system","content":"food schema"},{"role":"user","content":[{"type":"text","text":"咖喱猪排饭"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,YWJj"}}]}]""")
        val input=messages.toString()
        val body=AiWire.body(AiService(model="vision",format="anthropic"),messages,4096)
        assertEquals("food schema",body.getString("system"));assertEquals(4096,body.getInt("max_tokens"))
        val content=body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("咖喱猪排饭",content.getJSONObject(0).getString("text"))
        val image=content.getJSONObject(1);assertEquals("image",image.getString("type"))
        assertEquals("image/jpeg",image.getJSONObject("source").getString("media_type"))
        assertEquals("YWJj",image.getJSONObject("source").getString("data"))
        assertEquals(input,messages.toString())
        val openai=AiWire.body(AiService(model="vision"),messages,4096)
        assertEquals(input,openai.getJSONArray("messages").toString());assertFalse(openai.has("system"))
    }
    @Test fun responseParsingOnlyUsesTextAndRejectsTruncation() {
        assertEquals("one\ntwo",AiWire.text("anthropic",JSONObject("""{"stop_reason":"end_turn","content":[{"type":"thinking","thinking":"private"},{"type":"text","text":"one"},{"type":"text","text":"two"}]}""")))
        assertEquals("ok",AiWire.text("openai",JSONObject("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}""")))
        for((format,raw) in listOf("anthropic" to """{"stop_reason":"max_tokens","content":[{"type":"text","text":"partial"}]}""","openai" to """{"choices":[{"finish_reason":"length","message":{"content":"partial"}}]}""")) {
            try {AiWire.text(format,JSONObject(raw));fail("Truncated response accepted")}catch(_:AiWire.IncompleteResponse){}
        }
    }
    @Test fun versionTwoServicesKeepCredentialsAndDefaults() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="test-ai-format-migration.db";context.deleteDatabase(name)
        val service=AiService(id="existing",name="Existing",baseUrl="https://example.com/v1",model="model",images=true)
        val saved=service.copy(encryptedKey=AiSecrets().encrypt(service.id,"synthetic-key"))
        try {
            Store(context,name).use {db->
                val store=FoodStore(db);store.service(saved);store.setting("vision",saved.id)
                db.writableDatabase.execSQL("ALTER TABLE ai_services RENAME TO ai_services_v3")
                db.writableDatabase.execSQL("CREATE TABLE ai_services(id TEXT PRIMARY KEY,name TEXT NOT NULL,url TEXT NOT NULL,model TEXT NOT NULL,secret TEXT NOT NULL,images INTEGER NOT NULL,version INTEGER NOT NULL,consent INTEGER NOT NULL)")
                db.writableDatabase.execSQL("INSERT INTO ai_services SELECT id,name,url,model,secret,images,version,consent FROM ai_services_v3")
                db.writableDatabase.execSQL("DROP TABLE ai_services_v3")
                db.writableDatabase.version=2
            }
            Store(context,name).use {db->
                val store=FoodStore(db);val restored=store.services().single()
                assertEquals(saved,restored);assertEquals("openai",restored.format)
                assertEquals("synthetic-key",AiSecrets().decrypt(restored));assertEquals(saved.id,store.setting("vision"))
                store.service(restored.copy(format="anthropic"));assertEquals("anthropic",store.services().single().format)
            }
        }finally {context.deleteDatabase(name)}
    }
}
