package app.scalemate.food

import org.json.JSONObject

/** A single SSE event. Transport framing and cancellation are owned by AiClient. */
object AiStream {
    data class Delta(val text:String="",val finished:Boolean=false)
    fun decode(format:String,data:String):Delta {
        if(data=="[DONE]")return Delta(finished=true)
        val o=JSONObject(data)
        require(!o.has("error"))
        return if(format=="anthropic")when(o.optString("type")) {
            "content_block_delta"->Delta(o.optJSONObject("delta")?.takeIf {it.optString("type")=="text_delta"}?.optString("text").orEmpty())
            "message_delta"->{require(o.optJSONObject("delta")?.optString("stop_reason")!="max_tokens");Delta()}
            "message_stop"->Delta(finished=true)
            "error"->error("Provider error")
            else->Delta()
        } else {
            val c=o.optJSONArray("choices")?.optJSONObject(0) ?: return Delta()
            require(c.optString("finish_reason")!="length"&&c.optString("finish_reason")!="content_filter")
            Delta(c.optJSONObject("delta")?.optString("content").orEmpty().let {if(it=="null")"" else it},c.optString("finish_reason")=="stop")
        }
    }
}
