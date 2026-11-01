package app.scalemate.food

import org.json.JSONArray
import org.json.JSONObject

/** A single SSE event. Transport framing and cancellation are owned by AiClient. */
object AiStream {
    data class Delta(val text:String="",val reasoning:String="",val finished:Boolean=false,val usage:AiUsage=AiUsage())
    data class Progress(
        val receivedChunks:Int,
        val receivedChars:Int,
        val usage:AiUsage=AiUsage(),
        val estimatedOutputTokens:Long=0,
        val reasoningChunks:Int=0,
        val reasoningChars:Int=0,
        val estimatedReasoningTokens:Long=0
    )
    data class Result(val text:String,val usage:AiUsage=AiUsage())

    internal fun tokenWeight(text:CharSequence):Double {
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
        return weighted
    }

    fun estimateOutputTokens(text:CharSequence):Long =
        if(text.isBlank())0 else kotlin.math.ceil(tokenWeight(text)).toLong().coerceAtLeast(1)

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
        val payload=data.trim()
        if(payload=="[DONE]")return Delta(finished=true)
        val o=JSONObject(payload)
        if(!o.isNull("error"))throw AiWire.ProviderError()
        return if(format=="anthropic")when(o.optString("type")) {
            "message_start"->Delta(usage=usage(o.optJSONObject("message")?.optJSONObject("usage")))
            "content_block_delta"->{
                val delta=o.optJSONObject("delta")
                when(delta?.optString("type")) {
                    "text_delta"->Delta(text=delta.optString("text"))
                    "thinking_delta"->Delta(reasoning=delta.optString("thinking"))
                    else->Delta()
                }
            }
            "message_delta"->{
                if(o.optJSONObject("delta")?.optString("stop_reason")=="max_tokens")throw AiWire.IncompleteResponse()
                Delta(usage=usage(o.optJSONObject("usage")))
            }
            "message_stop"->Delta(finished=true)
            "error"->throw AiWire.ProviderError()
            else->Delta()
        } else {
            val usage=usage(o.optJSONObject("usage"))
            val c=o.optJSONArray("choices")?.optJSONObject(0) ?: return Delta(usage=usage)
            if(c.optString("finish_reason")=="length")throw AiWire.IncompleteResponse()
            if(c.optString("finish_reason")=="content_filter")throw AiWire.ContentFiltered()
            val delta=c.optJSONObject("delta")
            Delta(
                text=contentText(delta?.opt("content")),
                reasoning=scalarText(delta?.opt("reasoning_content")).ifEmpty {scalarText(delta?.opt("reasoning"))},
                finished=c.optString("finish_reason")=="stop",
                usage=usage
            )
        }
    }

    /** Some compatible gateways return content as a string, others as an array of text blocks. */
    private fun contentText(value:Any?):String = when(value) {
        is String -> value.takeUnless {it=="null"}.orEmpty()
        is JSONArray -> (0 until value.length()).mapNotNull {index->
            val block=value.optJSONObject(index) ?: return@mapNotNull null
            when(block.optString("type")) {
                "text","output_text"->block.optString("text")
                else->null
            }
        }.joinToString("")
        else->""
    }

    private fun scalarText(value:Any?):String=when(value) {
        is String -> value.takeUnless {it=="null"}.orEmpty()
        else->""
    }
}
