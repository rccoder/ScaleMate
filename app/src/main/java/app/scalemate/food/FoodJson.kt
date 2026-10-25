package app.scalemate.food

import org.json.JSONArray
import org.json.JSONObject

object FoodJson {
    class EmptyRecognition:Exception()
    class JsonNotFound:Exception()

    private fun embeddedJson(raw:String):JSONObject {
        val text=raw.trim().removePrefix("\uFEFF")
        runCatching {return JSONObject(text)}
        var start=text.indexOf('{')
        while(start>=0) {
            var depth=0
            var quoted=false
            var escaped=false
            for(index in start until text.length) {
                val char=text[index]
                if(quoted) {
                    when {
                        escaped->escaped=false
                        char=='\\'->escaped=true
                        char=='"'->quoted=false
                    }
                    continue
                }
                when(char) {
                    '"'->quoted=true
                    '{'->depth++
                    '}'->{
                        depth--
                        if(depth==0) {
                            runCatching {return JSONObject(text.substring(start,index+1))}
                            break
                        }
                    }
                }
            }
            start=text.indexOf('{',start+1)
        }
        throw JsonNotFound()
    }

    private fun JSONObject.strings(key:String):List<String> = optJSONArray(key)?.let {a->(0 until a.length()).map {a.getString(it)}}.orEmpty()
    private fun items(values:List<FoodItem>)=JSONArray().apply {values.forEach {put(item(it))}}
    private fun foodItems(a:JSONArray)=(0 until a.length()).map {item(a.getJSONObject(it))}

    private fun JSONObject.optionalText(key:String)=if(isNull(key)) "" else optString(key).trim()
    private fun JSONObject.number(key: String): Double? = if(isNull(key)||!has(key)) null else getDouble(key)
    private fun JSONObject.longOrNull(key:String):Long?=if(isNull(key)||!has(key))null else optLong(key)
    private fun JSONObject.optionalObject(key:String)=optJSONObject(key)?.takeUnless {it.length()==0}
    private fun advice(a:MealAdvice)=JSONObject().put("healthiness",a.healthiness).put("balance",a.balance).put("nextTime",a.nextTime).put("note",a.note)
    private fun advice(j:JSONObject?)=if(j==null)MealAdvice() else MealAdvice(j.optionalText("healthiness"),j.optionalText("balance"),j.optionalText("nextTime"),j.optionalText("note"))
    private fun usage(u:AiUsage)=JSONObject().put("inputTokens",u.inputTokens ?: JSONObject.NULL).put("outputTokens",u.outputTokens ?: JSONObject.NULL).put("totalTokens",u.totalTokens ?: JSONObject.NULL)
    private fun usage(j:JSONObject?)=if(j==null)AiUsage() else AiUsage(j.longOrNull("inputTokens"),j.longOrNull("outputTokens"),j.longOrNull("totalTokens"))
    fun nutrients(n: Nutrients) = JSONObject().put("kcal",n.kcal ?: JSONObject.NULL).put("protein",n.protein ?: JSONObject.NULL).put("carbs",n.carbs ?: JSONObject.NULL).put("fat",n.fat ?: JSONObject.NULL)
    fun nutrients(j: JSONObject) = Nutrients(j.number("kcal"),j.number("protein"),j.number("carbs"),j.number("fat"))
    private fun estimate(c:FoodEstimateContext)=JSONObject().put("shareCount",c.shareCount).put("consumedFraction",c.consumedFraction).put("fractionBasis",c.fractionBasis)
        .put("wholeGrams",c.wholeGrams ?: JSONObject.NULL).put("wholeNutrients",nutrients(c.wholeNutrients)).put("tags",JSONArray(c.tags)).put("description",c.description).put("interpretation",c.interpretation).put("warning",c.warning).put("resultMode",c.resultMode).put("summary",c.summary).put("reportedGrams",c.reportedGrams ?: JSONObject.NULL).put("reportedNutrients",nutrients(c.reportedNutrients))
    private fun estimate(j:JSONObject)=FoodEstimateContext(j.optInt("shareCount",1),j.optDouble("consumedFraction",1.0),j.optString("fractionBasis","own"),j.number("wholeGrams"),nutrients(j.getJSONObject("wholeNutrients")),j.strings("tags"),j.optionalText("description"),j.optionalText("interpretation"),j.optionalText("warning"),j.optString("resultMode","legacy"),j.optionalText("summary"),j.number("reportedGrams"),j.optJSONObject("reportedNutrients")?.let {nutrients(it)} ?: Nutrients())
    fun item(f: FoodItem): JSONObject = JSONObject().put("id",f.id).put("name",f.name).put("portion",f.portion)
        .put("grams",f.grams ?: JSONObject.NULL).put("base",nutrients(f.base)).put("shareCount",f.shareCount)
        .put("quantity",f.quantity).put("fraction",f.fraction).put("basis",f.basis).put("source",f.source).put("assumptions",f.assumptions).put("needsReview",f.needsPortionReview).put("photos",JSONArray(f.photos)).put("libraryId",f.libraryId).put("eatingAdvice",f.eatingAdvice).put("nextTimeAdvice",f.nextTimeAdvice).put("positiveFeedback",f.positiveFeedback).put("estimateContext",f.estimateContext?.let {estimate(it)} ?: JSONObject.NULL)
    fun item(j: JSONObject) = FoodItem(j.getString("id"),j.getString("name"),j.optString("portion","1"),j.number("grams"),nutrients(j.getJSONObject("base")),j.optInt("shareCount",1),j.optDouble("fraction",1.0),j.optDouble("quantity",1.0),j.optString("basis","whole"),j.optString("source","manual"),j.optString("assumptions"),j.optBoolean("needsReview"),j.strings("photos"),j.optString("libraryId"),j.optionalText("eatingAdvice"),j.optionalText("nextTimeAdvice"),j.optionalText("positiveFeedback"),j.optJSONObject("estimateContext")?.let {estimate(it)})
    fun meal(m: Meal): JSONObject = JSONObject().put("id",m.id).put("revision",m.revision).put("time",m.time).put("offset",m.offset).put("mealType",m.mealType)
        .put("items",JSONArray().apply {m.items.forEach {put(item(it))}}).put("advice",advice(m.advice)).put("usage",usage(m.usage)).put("analysisMs",m.analysisMs ?: JSONObject.NULL)
    fun meal(j: JSONObject): Meal = Meal(id=j.getString("id"),revision=j.getLong("revision"),time=j.getLong("time"),offset=j.getInt("offset"),mealType=j.getInt("mealType"),items=j.getJSONArray("items").let {a->(0 until a.length()).map {item(a.getJSONObject(it))}},advice=advice(j.optionalObject("advice")),usage=usage(j.optionalObject("usage")),analysisMs=j.longOrNull("analysisMs"))
    fun batch(b:FoodBatch)=JSONObject().put("id",b.id).put("kind",b.kind).put("photos",JSONArray(b.photos)).put("description",b.description).put("tags",JSONArray(b.tags.toList())).put("shareCount",b.shareCount).put("results",items(b.results)).put("status",b.status).put("editingItemId",b.editingItemId ?: JSONObject.NULL).put("advice",advice(b.advice)).put("usage",usage(b.usage)).put("analysisMs",b.analysisMs ?: JSONObject.NULL)
    fun batch(j:JSONObject)=FoodBatch(id=j.getString("id"),kind=j.optString("kind","photo"),photos=j.strings("photos"),description=j.optString("description"),tags=j.strings("tags").toSet(),shareCount=j.optInt("shareCount",1).coerceIn(1,20),results=foodItems(j.optJSONArray("results") ?: JSONArray()),status=if(j.optString("status")=="analyzing")"interrupted" else j.optString("status","idle"),editingItemId=if(j.isNull("editingItemId"))null else j.getString("editingItemId"),advice=advice(j.optionalObject("advice")),usage=usage(j.optionalObject("usage")),analysisMs=j.longOrNull("analysisMs"))
    fun draft(d: FoodDraft) = JSONObject().put("meal",meal(d.meal)).put("batch",d.batch?.let {batch(it)} ?: JSONObject.NULL).put("editing",d.editing).put("updatedAt",d.updatedAt).toString()
    fun draft(raw: String): FoodDraft {
        val j=JSONObject(raw);val original=meal(j.getJSONObject("meal"))
        val pending=original.items.filter {it.needsPortionReview}
        val addition=if(!j.isNull("batch"))batch(j.getJSONObject("batch")) else if(!j.isNull("photo")||pending.isNotEmpty())FoodBatch(
            photos=listOfNotNull(if(j.isNull("photo"))null else j.getString("photo")),
            description=j.optString("description"),tags=j.strings("tags").toSet(),results=pending,
            status=if(pending.isEmpty())"idle" else "review",
            advice=original.advice,usage=original.usage,analysisMs=original.analysisMs
        ) else null
        return FoodDraft(meal=original.copy(items=original.items.filterNot {it.needsPortionReview}),editing=j.optBoolean("editing"),updatedAt=j.optLong("updatedAt",System.currentTimeMillis()),batch=addition)
    }
    data class Recognition(val items:List<FoodItem>,val advice:MealAdvice=MealAdvice())
    /** AI returns final personal intake. Never divide or scale these estimates in the app. */
    fun recognitionPersonal(raw:String,sharing:Int,photos:List<String> = emptyList(),tags:Set<String> = emptySet(),description:String=""):List<FoodItem> =
        recognition(raw,sharing,photos,tags,description).items
    fun recognition(raw:String,sharing:Int,photos:List<String> = emptyList(),tags:Set<String> = emptySet(),description:String=""):Recognition {
        require(sharing in 1..20)
        val root=embeddedJson(raw)
        require(root.optString("calculationMode")=="personal")
        val array=root.getJSONArray("foods")
        if(array.length()==0)throw EmptyRecognition()
        require(array.length() in 1..30)
        val items=(0 until array.length()).map {i->
            val j=array.getJSONObject(i)
            val personal=nutrients(j.getJSONObject("personalNutrients"));require(personal.valid()&&personal.hasValue())
            val grams=j.number("personalGrams");require(grams==null||grams.isFinite()&&grams>0)
            val name=j.getString("name").trim();require(name.isNotEmpty()&&name.length<=120)
            val indices=j.optJSONArray("imageIndices")?.let {a->(0 until a.length()).map {a.getInt(it)}.distinct()} ?: photos.indices.toList()
            require(photos.isEmpty()||indices.all {it in photos.indices})
            val portion=grams?.let {java.math.BigDecimal.valueOf(it).setScale(2,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()+" g"} ?: j.optionalText("personalPortion").take(100).ifBlank {"1"}
            FoodItem(name=name,portion=portion,grams=grams,base=personal,source="ai",assumptions=j.optionalText("assumptions").take(1000),
                eatingAdvice=j.optionalText("eatingAdvice").take(600),nextTimeAdvice=j.optionalText("nextTimeAdvice").take(600),positiveFeedback=j.optionalText("positiveFeedback").take(300),
                needsPortionReview=true,photos=indices.mapNotNull {photos.getOrNull(it)},estimateContext=FoodEstimateContext(
                    shareCount=sharing,tags=tags.toList(),description=description,interpretation=j.optionalText("contextInterpretation").take(1000),warning=j.optionalText("contextWarning").take(200),
                    resultMode="personal",summary=j.optionalText("intakeSummary").take(240),reportedGrams=grams,reportedNutrients=personal))
        }
        return Recognition(items,advice(root.optionalObject("mealAdvice")))
    }
    fun usage(raw:String):AiUsage {
        val root=runCatching {embeddedJson(raw)}.getOrNull() ?: return AiUsage()
        val j=root.optJSONObject("usage") ?: return AiUsage()
        fun value(vararg keys:String):Long?=keys.firstNotNullOfOrNull {key->j.optLong(key,-1).takeIf {it>=0}}
        return AiUsage(value("input_tokens","prompt_tokens"),value("output_tokens","completion_tokens"),value("total_tokens"))
    }
}
