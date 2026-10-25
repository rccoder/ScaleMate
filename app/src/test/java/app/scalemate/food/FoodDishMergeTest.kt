package app.scalemate.food

import org.junit.Assert.*
import org.junit.Test

class FoodDishMergeTest {
    private fun food(name:String,kcal:Double,grams:Double?=null)=FoodItem(
        name=name,base=Nutrients(kcal,10.0,5.0,2.0),grams=grams,source="ai",needsPortionReview=true,
        photos=listOf("meal.jpg"),estimateContext=FoodEstimateContext(
            shareCount=2,resultMode="personal",summary="Personal serving",reportedNutrients=Nutrients(kcal,10.0,5.0,2.0),reportedGrams=grams
        )
    )
    private fun batch(vararg items:FoodItem)=FoodBatch(results=items.toList(),status="review",shareCount=2)
    private fun reject(block:()->Unit) {try {block();fail("Invalid merge accepted")}catch(_:IllegalArgumentException){}}

    @Test fun lightMealComponentsBecomeOneDishAndSeparateDrinkStaysUnchanged() {
        val chicken=food("chicken",180.0,100.0)
        val milk=food("milk",120.0,200.0)
        val vegetables=food("vegetables",40.0,150.0)
        val dressing=food("dressing",80.0,20.0)
        val original=batch(chicken,milk,vegetables,dressing).copy(advice=MealAdvice(balance="Add variety"),usage=AiUsage(totalTokens=456),analysisMs=123)
        val before=FoodDraft(meal=Meal(items=listOf(food("rice",100.0).copy(needsPortionReview=false))),batch=original)
        val changed=original.mergeDish(setOf(chicken.id,vegetables.id,dressing.id)," Chicken salad ","My serving")
        assertEquals(listOf("Chicken salad","milk"),changed.results.map {it.name})
        val dish=changed.results.first()
        assertEquals(300.0,dish.nutrients.kcal!!,0.0);assertEquals(270.0,dish.grams!!,0.0)
        assertEquals(1.0,dish.factor,0.0);assertEquals(1.0,dish.estimateContext!!.factor,0.0)
        assertEquals(300.0,dish.reuse().nutrients.kcal!!,0.0)
        assertEquals(milk,changed.results[1]);assertEquals(listOf("meal.jpg"),dish.photos)
        assertTrue(dish.needsPortionReview);assertEquals("",dish.libraryId)
        assertEquals(original.advice,changed.advice);assertEquals(original.usage,changed.usage);assertEquals(original.analysisMs,changed.analysisMs)
        assertEquals(4,original.results.size);assertEquals(1,before.meal.items.size)
        val committed=before.copy(batch=changed.copy(results=changed.results.map {it.copy(needsPortionReview=false)})).commitBatch()
        assertEquals(listOf("rice","Chicken salad","milk"),committed.meal.items.map {it.name})
        assertEquals(520.0,committed.meal.nutrients.kcal!!,0.0)
    }

    @Test fun partialNutrientsAndUnknownGramsStayUnknown() {
        val a=food("chicken",180.0,100.0)
        val b=food("vegetables",40.0).copy(base=Nutrients(40.0,null,5.0,0.0))
        val dish=batch(a,b).mergeDish(setOf(a.id,b.id),"Salad","One serving").results.single()
        assertEquals(220.0,dish.nutrients.kcal!!,0.0);assertNull(dish.nutrients.protein);assertNull(dish.grams)
        assertNull(dish.estimateContext!!.reportedGrams)
    }

    @Test fun editedAndLegacyAmountsAreNormalizedOnceAndInputExplanationsSurvive() {
        val a=food("chicken",180.0,100.0).copy(quantity=2.0,eatingAdvice="Less dressing",assumptions="Grilled",estimateContext=FoodEstimateContext(resultMode="personal",reportedNutrients=Nutrients(180.0),summary="Half the shared dish"))
        val b=food("vegetables",80.0,200.0).copy(shareCount=2,basis="own",fraction=.5,positiveFeedback="Vegetable variety")
        val dish=batch(a,b).mergeDish(setOf(a.id,b.id),"Salad","Personal serving").results.single()
        assertEquals(380.0,dish.nutrients.kcal!!,0.0);assertEquals(250.0,dish.grams!!,0.0)
        assertEquals(380.0,dish.reuse().nutrients.kcal!!,0.0)
        assertTrue(dish.eatingAdvice.contains("Less dressing"));assertEquals("",dish.positiveFeedback)
        assertTrue(dish.assumptions.contains("Grilled"));assertTrue(dish.estimateContext!!.summary.contains("Half the shared dish"))
    }

    @Test fun cancellationAndInvalidSelectionsDoNotModifyPendingResults() {
        val a=food("chicken",180.0);val b=food("vegetables",40.0);val original=batch(a,b)
        reject {original.mergeDish(setOf(a.id),"Salad","One serving")}
        reject {original.mergeDish(setOf(a.id,"missing"),"Salad","One serving")}
        reject {original.mergeDish(setOf(a.id,b.id)," ","One serving")}
        reject {original.mergeDish(setOf(a.id,b.id),"Salad","")}
        reject {original.copy(status="analyzing").mergeDish(setOf(a.id,b.id),"Salad","One serving")}
        reject {original.copy(kind="edit",editingItemId=a.id).mergeDish(setOf(a.id,b.id),"Salad","One serving")}
        assertEquals(listOf(a,b),original.results)
    }

    @Test fun invalidTotalsCannotHideBehindAMerge() {
        val a=food("chicken",8_000_000.0);val b=food("vegetables",8_000_000.0)
        reject {batch(a,b).mergeDish(setOf(a.id,b.id),"Salad","One serving")}
        val c=a.copy(base=Nutrients(kcal=100.0));val d=b.copy(base=Nutrients(protein=10.0))
        reject {batch(c,d).mergeDish(setOf(c.id,d.id),"Salad","One serving")}
        reject {batch(c,d.copy(base=Nutrients(-1.0))).mergeDish(setOf(c.id,d.id),"Salad","One serving")}
    }

    @Test fun mergedDishSurvivesDraftAndMealJsonRoundTrip() {
        val a=food("chicken",180.0,100.0);val b=food("vegetables",40.0,150.0)
        val merged=batch(a,b).copy(description="No sauce",tags=setOf("shared")).mergeDish(setOf(a.id,b.id),"Salad","My serving")
        val draft=FoodDraft(batch=merged)
        assertEquals(draft,FoodJson.draft(FoodJson.draft(draft)))
        val meal=draft.copy(batch=merged.copy(results=merged.results.map {it.copy(needsPortionReview=false)})).commitBatch().meal
        assertEquals(meal,FoodJson.meal(FoodJson.meal(meal)))
        assertEquals("Salad",meal.items.single().reuse().name)
        assertEquals("No sauce",meal.items.single().estimateContext!!.description)
    }

    @Test fun sharingOnePhotoNeverAutomaticallyMergesSeparateDishes() {
        val raw="""{"calculationMode":"personal","foods":[{"name":"salad","imageIndices":[0],"personalNutrients":{"kcal":300}},{"name":"milk","imageIndices":[0],"personalNutrients":{"kcal":120}}]}"""
        val parsed=FoodJson.recognition(raw,2,photos=listOf("meal.jpg"))
        assertEquals(listOf("salad","milk"),parsed.items.map {it.name})
        assertEquals(listOf(300.0,120.0),parsed.items.map {it.nutrients.kcal})
    }
}
