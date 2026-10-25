package app.scalemate.food

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AiWireTest {
    @Test fun openAiTextAcceptsStringOrContentBlocks() {
        val plain=JSONObject("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}""")
        val blocks=JSONObject("""{"choices":[{"finish_reason":"stop","message":{"content":[{"type":"text","text":"one"},{"type":"output_text","text":"two"}]}}]}""")
        assertEquals("ok",AiWire.text("openai",plain))
        assertEquals("one\ntwo",AiWire.text("openai",blocks))
    }

    @Test fun emptyAndTruncatedResponsesHaveDistinctFailures() {
        assertThrows(AiWire.EmptyResponse::class.java) {
            AiWire.text("openai",JSONObject("""{"choices":[{"finish_reason":"stop","message":{"content":""}}]}"""))
        }
        assertThrows(AiWire.IncompleteResponse::class.java) {
            AiWire.text("openai",JSONObject("""{"choices":[{"finish_reason":"length","message":{"content":"{\"calculationMode\""}}]}"""))
        }
    }
}
