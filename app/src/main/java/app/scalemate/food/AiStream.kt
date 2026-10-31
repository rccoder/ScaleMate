package app.scalemate.food

import org.json.JSONObject

/** A single SSE event. Transport framing and cancellation are owned by AiClient. */
object AiStream {
    data class Delta(val text:String="",val finished:Boolean=false,val usage:AiUsage=AiUsage())
    data class Progress(
        val receivedChunks:Int,
        val receivedChars:Int,
        val usage:AiUsage=AiUsage(),
        val estimatedOutputTokens:Long=0
    )
    data class Result(val text:String,val usage:AiUsage=AiUsage())

    fun estimateOutputTokens(text:CharSequence):Long {
        if(text.isBlank())return 0
        var weighted=0.0
        var index=0
        while(index<text.length) {
            val codePoint=Character.codePointAt(text,index)
            weighted+=when {
                Character.isWhitespace(codePoint)->0.1
                codePoint<=0x7f->0.28
                else->1.0
            }
            index+=Character.charCount(codePoint)
        }
        return kotlin.math.ceil(weighted).toLong().coerceAtLeast(1)
    }

    fun usage(value:JSONObject?):AiUsage {
        if(value==null)return AiUsage()
        fun number(vararg keys:String):Long?=keys.firstNotNullOfOrNull {key->value.optLong(key,-1).takeIf {it>=0}}
        return AiUsage(
            number("input_tokens","prompt_tokens"),
            number("output_tokens","completion_tokens"),
            number("total_tokens")
        )
    }

    fun merge(current:AiUsage,latest:AiUsage)=AiUsage(
        latest.inputTokens ?: current.inputTokens,
        latest.outputTokens ?: current.outputTokens,
        latest.totalTokens ?: current.totalTokens
    )

    fun decode(format:String,data:String):Delta {
        if(data=="[DONE]")return Delta(finished=true)
        val o=JSONObject(data)
        require(!o.has("error"))
        return if(format=="anthropic")when(o.optString("type")) {
            "message_start"->Delta(usage=usage(o.optJSONObject("message")?.optJSONObject("usage")))
            "content_block_delta"->Delta(o.optJSONObject("delta")?.takeIf {it.optString("type")=="text_delta"}?.optString("text").orEmpty())
            "message_delta"->{
                if(o.optJSONObject("delta")?.optString("stop_reason")=="max_tokens")throw AiWire.IncompleteResponse()
                Delta(usage=usage(o.optJSONObject("usage")))
            }
            "message_stop"->Delta(finished=true)
            "error"->error("Provider error")
            else->Delta()
        } else {
            val usage=usage(o.optJSONObject("usage"))
            val c=o.optJSONArray("choices")?.optJSONObject(0) ?: return Delta(usage=usage)
            if(c.optString("finish_reason")=="length")throw AiWire.IncompleteResponse()
            require(c.optString("finish_reason")!="content_filter")
            Delta(c.optJSONObject("delta")?.optString("content").orEmpty().let {if(it=="null")"" else it},c.optString("finish_reason")=="stop",usage)
        }
    }
}
