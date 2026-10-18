package app.scalemate.food

import java.time.Instant
import java.time.ZoneId
import java.util.UUID

fun defaultMealType(hour:Int=java.time.LocalTime.now().hour): Int = when(hour){in 5..10->1;in 11..14->2;in 17..21->3;else->4}

fun newFoodId(): String = UUID.randomUUID().toString()

data class Nutrients(val kcal: Double? = null, val protein: Double? = null, val carbs: Double? = null, val fat: Double? = null) {
    fun valid() = listOf(kcal,protein,carbs,fat).all { it == null || (it.isFinite() && it >= 0 && it<=10_000_000) }
    fun hasValue() = listOf(kcal,protein,carbs,fat).any { it != null }
    fun scaled(factor: Double) = Nutrients(kcal?.times(factor),protein?.times(factor),carbs?.times(factor),fat?.times(factor))
    companion object {
        // A partially known total remains unknown rather than treating a missing item as zero.
        fun sum(values: List<Nutrients>): Nutrients {
            fun total(get: (Nutrients)->Double?): Double? = if(values.isNotEmpty() && values.all {get(it)!=null}) values.sumOf {get(it)!!} else null
            return Nutrients(total{it.kcal},total{it.protein},total{it.carbs},total{it.fat})
        }
    }
}

data class FoodEstimateContext(
    val shareCount:Int=1,val consumedFraction:Double=1.0,val fractionBasis:String="own",
    val wholeGrams:Double?=null,val wholeNutrients:Nutrients=Nutrients(),
    val tags:List<String> = emptyList(),val description:String="",val interpretation:String="",val warning:String="",val resultMode:String="legacy",val summary:String="",val reportedGrams:Double?=null,val reportedNutrients:Nutrients=Nutrients()
) {
    val factor get()=if(resultMode=="personal")1.0 else consumedFraction/if(fractionBasis=="own")shareCount else 1
}

data class FoodItem(
    val id: String = newFoodId(), val name: String = "", val portion: String = "1", val grams: Double? = null,
    val base: Nutrients = Nutrients(), val shareCount: Int = 1, val fraction: Double = 1.0,
    val quantity: Double = 1.0, val basis: String = "whole", val source: String = "manual", val assumptions: String = "", val needsPortionReview: Boolean = false,
    val photos:List<String> = emptyList(),val libraryId:String="",val eatingAdvice:String="",val nextTimeAdvice:String="",val positiveFeedback:String="",val estimateContext:FoodEstimateContext?=null
) {
    val hasEatingSuggestions get()=eatingAdvice.isNotBlank()||nextTimeAdvice.isNotBlank()||positiveFeedback.isNotBlank()
    val factor get() = quantity * fraction / if(basis == "own") shareCount else 1
    val nutrients get() = base.scaled(factor)
    fun valid() = name.isNotBlank() && name.length<=120 && base.valid() && base.hasValue() &&
        nutrients.valid() && quantity.isFinite() && quantity>0 && quantity<=1000 && shareCount in 1..20 && fraction.isFinite() && fraction>0 && fraction<=1 && basis in listOf("whole","own") &&
        (grams==null || grams.isFinite() && grams>0) && !needsPortionReview
    /** Copy the confirmed amount as a new base, preventing another application of old sharing factors. */
    fun reuse(): FoodItem {
        val amount=grams?.times(factor)
        fun display(value:Double)=java.math.BigDecimal.valueOf(value).setScale(3,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        val confirmedPortion=if(factor==1.0)portion else amount?.let {"${display(it)} g"} ?: "$portion × ${display(factor)}"
        return copy(id=newFoodId(),base=nutrients,grams=amount,portion=confirmedPortion,shareCount=1,fraction=1.0,quantity=1.0,basis="whole",needsPortionReview=false)
    }
}

data class Meal(
    val id: String = newFoodId(), val revision: Long = 1, val time: Long = System.currentTimeMillis(),
    val offset: Int = ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(time)).totalSeconds,
    val mealType: Int = defaultMealType(), val items: List<FoodItem> = emptyList(), val status: String = "pending",
    val platformId: String = "", val attempts: Int = 0, val deleted: Boolean = false
) {
    val nutrients get() = Nutrients.sum(items.map {it.nutrients})
    val title get() = items.joinToString("、"){it.name}.take(160)
}
data class FoodTemplate(val id: String=newFoodId(),val item: FoodItem)
data class FoodBatch(
    val id:String=newFoodId(),val kind:String="photo",val photos:List<String> = emptyList(),
    val description:String="",val tags:Set<String> = emptySet(),val shareCount:Int=1,
    val results:List<FoodItem> = emptyList(),val status:String="idle",val editingItemId:String?=null
) {
    fun validForCommit()=results.isNotEmpty()&&results.all {it.valid()}&&Nutrients.sum(results.map {it.nutrients}).valid()
}
data class FoodDraft(val meal:Meal=Meal(),val editing:Boolean=false,val updatedAt:Long=System.currentTimeMillis(),val batch:FoodBatch?=null) {
    fun isEmpty()=meal.items.isEmpty()&&(batch==null||batch.photos.isEmpty()&&batch.results.isEmpty()&&batch.description.isBlank())
    fun removeBatchResult(id:String):FoodDraft {
        val pending=batch ?: return this
        if(pending.results.none {it.id==id})return this
        val remaining=pending.results.filterNot {it.id==id}
        return if(remaining.isNotEmpty())copy(batch=pending.copy(results=remaining)) else copy(
            meal=if(pending.editingItemId!=null)meal.copy(items=meal.items.filterNot {it.id==pending.editingItemId}) else meal,
            batch=null)
    }
    fun commitBatch():FoodDraft {
        val pending=batch ?: error("No pending addition")
        require(pending.validForCommit())
        val items=if(pending.editingItemId!=null) {
            require(meal.items.any {it.id==pending.editingItemId}&&pending.results.size==1)
            meal.items.map {if(it.id==pending.editingItemId)pending.results.single().copy(id=it.id) else it}
        } else meal.items+pending.results
        return copy(meal=meal.copy(items=items),batch=null,updatedAt=System.currentTimeMillis())
    }
}
data class FoodLibraryEntry(val id:String=newFoodId(),val item:FoodItem,val favorite:Boolean=false,val useCount:Long=0,val lastUsed:Long=0)
object FoodLibraryRules {
    const val RECENT_LIMIT=500
    fun nameKey(name:String)=java.text.Normalizer.normalize(name,java.text.Normalizer.Form.NFKC).trim().replace(Regex("\\s+")," ").lowercase(java.util.Locale.ROOT)
    fun sorted(entries:List<FoodLibraryEntry>)=entries.sortedWith(compareByDescending<FoodLibraryEntry>{it.favorite}.thenByDescending {it.useCount}.thenByDescending {it.lastUsed}.thenBy {it.id})
    fun retained(entries:List<FoodLibraryEntry>):List<FoodLibraryEntry> = entries.filter {it.favorite}+entries.filter {!it.favorite}.sortedWith(compareByDescending<FoodLibraryEntry>{it.lastUsed}.thenBy {it.id}).take(RECENT_LIMIT)
}
data class AiModelOption(val id:String,val name:String="")
data class AiModelCatalog(val models:List<AiModelOption>,val truncated:Boolean=false)
data class AiService(
    val id: String=newFoodId(),val name: String="",val baseUrl: String="",val model: String="",val encryptedKey: String="",
    val images: Boolean=false,val version: Long=1,val consentVersion: Long=0,val format:String="openai"
)
data class FoodState(
    val loaded: Boolean=false,val meals: List<Meal> = emptyList(),val templates: List<FoodTemplate> = emptyList(),
    val library:List<FoodLibraryEntry> = emptyList(),val services: List<AiService> = emptyList(),val draft: FoodDraft?=null,val defaultVision: String="",val defaultText: String="",
    val selectedService: String="",val permitted: Boolean=false,val healthAvailable:Boolean=true,val busy: Boolean=false,val saving: Boolean=false,
    val error: Int?=null,val message: Int?=null
)
