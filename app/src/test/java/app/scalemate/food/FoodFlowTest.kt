package app.scalemate.food

import org.junit.Assert.*
import org.junit.Test

class FoodFlowTest {
    private fun item(name:String="咖喱猪排饭")=FoodItem(name=name,base=Nutrients(650.0,25.0,85.0,20.0))
    @Test fun pendingResultsNeverCountBeforeConfirmationAndCannotCommitTwice() {
        val draft=FoodDraft(batch=FoodBatch(results=listOf(item()),status="review"))
        assertTrue(draft.meal.items.isEmpty())
        val confirmed=draft.commitBatch()
        assertEquals(1,confirmed.meal.items.size);assertNull(confirmed.batch)
        try {confirmed.commitBatch();fail("An addition was committed twice")}catch(_:IllegalStateException){}
    }
    @Test fun reanalysisReplacesOnlyPendingResultsAndEditReplacesOriginal() {
        val existing=item();val draft=FoodDraft(meal=Meal(items=listOf(existing)),batch=FoodBatch(results=listOf(item("米饭"))))
        val replaced=draft.copy(batch=draft.batch!!.copy(results=listOf(item("鸡蛋"))))
        assertEquals(listOf("咖喱猪排饭","鸡蛋"),replaced.commitBatch().meal.items.map {it.name})
        val edited=draft.copy(batch=FoodBatch(results=listOf(existing.copy(base=Nutrients(500.0))),editingItemId=existing.id)).commitBatch()
        assertEquals(1,edited.meal.items.size);assertEquals(existing.id,edited.meal.items.single().id);assertEquals(500.0,edited.meal.nutrients.kcal!!,0.001)
    }
    @Test fun invalidOrUnreviewedResultsCannotCommit() {
        for(food in listOf(item().copy(needsPortionReview=true),item().copy(base=Nutrients(-1.0)))) {
            try {FoodDraft(batch=FoodBatch(results=listOf(food))).commitBatch();fail("Invalid food committed")}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun favoritesAreExtraToFiveHundredAndSortBeforeUsage() {
        val ordinary=(0..500).map {FoodLibraryEntry(id="food-$it",item=item("食物$it"),useCount=it.toLong(),lastUsed=it.toLong())}
        val favorite=FoodLibraryEntry(id="favorite",item=item(),favorite=true,useCount=0,lastUsed=0)
        val retained=FoodLibraryRules.retained(ordinary+favorite)
        assertEquals(501,retained.size);assertFalse(retained.any {it.id=="food-0"});assertTrue(retained.contains(favorite))
        assertEquals(favorite,FoodLibraryRules.sorted(retained).first())
        assertEquals("food-500",FoodLibraryRules.sorted(retained)[1].id)
    }
    @Test fun recencyBreaksUsageTiesAndNamesNormalize() {
        val a=FoodLibraryEntry(id="a",item=item(),useCount=2,lastUsed=1)
        val b=a.copy(id="b",lastUsed=2)
        assertEquals("b",FoodLibraryRules.sorted(listOf(a,b)).first().id)
        assertEquals(FoodLibraryRules.nameKey("  Curry  RICE "),FoodLibraryRules.nameKey("ｃｕｒｒｙ rice"))
    }
    @Test fun mealTypeDefaultsUseLocalHours() {
        assertEquals(1,defaultMealType(8));assertEquals(2,defaultMealType(12));assertEquals(3,defaultMealType(19));assertEquals(4,defaultMealType(23))
    }
    @Test fun removingPendingFoodPreservesOtherItemsAndClearsLastBatchAtomically() {
        val original=item();val added=item("水煮蛋");val second=item("米饭")
        val draft=FoodDraft(meal=Meal(items=listOf(original)),batch=FoodBatch(results=listOf(added,second)))
        val one=draft.removeBatchResult(added.id)
        assertEquals(listOf(second),one.batch!!.results);assertEquals(listOf(original),one.meal.items)
        val none=one.removeBatchResult(second.id)
        assertNull(none.batch);assertEquals(listOf(original),none.meal.items)
        val editing=draft.copy(batch=FoodBatch(results=listOf(original),editingItemId=original.id)).removeBatchResult(original.id)
        assertNull(editing.batch);assertTrue(editing.meal.items.isEmpty())
        assertEquals(draft,draft.removeBatchResult("missing"))
    }

}
