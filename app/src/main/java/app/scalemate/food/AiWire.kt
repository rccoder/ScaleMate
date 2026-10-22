package app.scalemate.food

import org.json.JSONArray
import org.json.JSONObject

/** Provider wire formats; callers supply only app-built messages, never arbitrary remote URLs. */
object AiWire {
    class IncompleteResponse:Exception()
    class EmptyResponse:Exception()

    fun body(service:AiService,messages:JSONArray,maxTokens:Int):JSONObject {
        require(service.format in listOf("openai","anthropic"))
        if(service.format=="openai")return JSONObject().put("model",service.model).put("messages",messages).put("stream",false)
        val system=mutableListOf<String>()
        val converted=JSONArray()
        for(i in 0 until messages.length()) {
            val message=messages.getJSONObject(i)
            if(message.getString("role")=="system") {system+=message.getString("content");continue}
            val content=message.get("content")
            val blocks=if(content is String)JSONArray().put(JSONObject().put("type","text").put("text",content)) else {
                val source=content as JSONArray
                JSONArray().apply {for(j in 0 until source.length()) {
                    val block=source.getJSONObject(j)
                    when(block.getString("type")) {
                        "text"->put(JSONObject().put("type","text").put("text",block.getString("text")))
                        "image_url"->{
                            val url=block.getJSONObject("image_url").getString("url")
                            require(url.startsWith("data:image/jpeg;base64,"))
                            put(JSONObject().put("type","image").put("source",JSONObject().put("type","base64").put("media_type","image/jpeg").put("data",url.substringAfter(','))))
                        }
                        else->error("Unsupported message block")
                    }
                }}
            }
            converted.put(JSONObject().put("role",message.getString("role")).put("content",blocks))
        }
        return JSONObject().put("model",service.model).put("max_tokens",maxTokens).put("messages",converted).put("stream",false).apply {if(system.isNotEmpty())put("system",system.joinToString("\n"))}
    }
    fun text(format:String,response:JSONObject):String {
        val result=if(format=="anthropic") {
            if(response.optString("stop_reason")=="max_tokens")throw IncompleteResponse()
            val blocks=response.getJSONArray("content")
            (0 until blocks.length()).map {blocks.getJSONObject(it)}.filter {it.optString("type")=="text"}.joinToString("\n"){it.getString("text")}
        } else {
            require(format=="openai")
            val choice=response.getJSONArray("choices").getJSONObject(0)
            if(choice.optString("finish_reason")=="length")throw IncompleteResponse()
            when(val content=choice.getJSONObject("message").opt("content")) {
                is String -> content
                is JSONArray -> (0 until content.length()).mapNotNull {index->
                    val block=content.optJSONObject(index) ?: return@mapNotNull null
                    if(block.optString("type") in listOf("text","output_text"))block.optString("text").takeIf {it.isNotBlank()} else null
                }.joinToString("\n")
                else -> ""
            }
        }
        if(result.isBlank())throw EmptyResponse()
        return result
    }
}
