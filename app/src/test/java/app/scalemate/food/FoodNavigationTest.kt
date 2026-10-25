package app.scalemate.food

import org.junit.Assert.*
import org.junit.Test

class FoodNavigationTest {
    private val food=FoodItem(name="咖喱猪排饭",base=Nutrients(650.0,25.0,85.0,20.0))
    private val review=FoodDraft(batch=FoodBatch(kind="photo",status="review",results=listOf(food)))
    private val meal=FoodDraft(meal=Meal(items=listOf(food)))

    @Test fun choosingResumeOpensTheEditorThatContainsTheDraft() {
        assertEquals("photo",foodDraftPage(review))
        assertEquals("photo",foodDraftPage(FoodDraft(batch=FoodBatch(photos=listOf("meal.jpg"),status="interrupted"))))
        assertEquals("photo",foodDraftPage(FoodDraft(batch=FoodBatch(description="米饭半份"))))
        assertEquals("review",foodDraftPage(FoodDraft(batch=FoodBatch(kind="manual",status="review",results=listOf(food)))))
        assertEquals("editor",foodDraftPage(meal))
        assertEquals("home",foodDraftPage(null))
        assertEquals("home",foodDraftPage(FoodDraft()))
        assertEquals("home",foodDraftPage(FoodDraft(batch=FoodBatch(kind="manual",status="review",results=listOf(FoodItem())))))
    }

    @Test fun startingARecordOffersAChoiceOnlyWhenTheDraftHasContent() {
        assertFalse(foodEntryNeedsChoice(null))
        assertFalse(foodEntryNeedsChoice(FoodDraft()))
        assertFalse(foodEntryNeedsChoice(FoodDraft(batch=FoodBatch(shareCount=2))))
        assertTrue(foodEntryNeedsChoice(meal))
        assertTrue(foodEntryNeedsChoice(review))
        assertTrue(foodEntryNeedsChoice(FoodDraft(batch=FoodBatch(description="少油"))))
        assertTrue(foodEntryNeedsChoice(FoodDraft(batch=FoodBatch(photos=listOf("meal.jpg")))))
    }

    @Test fun returningFromPhotosProtectsPendingResultsButCanCancelAnUnanalysedBatch() {
        val reviewed=foodBackAction("photo",review.batch)
        assertTrue(reviewed.confirmDiscard)
        assertFalse(reviewed.discardBatch)
        val beforeAnalysis=foodBackAction("photo",FoodBatch(photos=listOf("meal.jpg")))
        assertEquals("editor",beforeAnalysis.page)
        assertTrue(beforeAnalysis.discardBatch)
        assertFalse(beforeAnalysis.confirmDiscard)
    }

    @Test fun backWithinThePanelReturnsToTheCorrectStepAndClosingKeepsTheDraft() {
        assertEquals(FoodBackAction("picker",discardBatch=true),foodBackAction("review",FoodBatch(kind="manual"),manualReturn="picker"))
        assertEquals(FoodBackAction("editor",discardBatch=true),foodBackAction("review",FoodBatch(kind="edit")))
        assertEquals(FoodBackAction("editor"),foodBackAction("picker",null))
        assertEquals(FoodBackAction("photo"),foodBackAction("services",null,servicesReturn="photo"))
        val close=foodBackAction("editor",null)
        assertNull(close.page)
        assertFalse(close.discardBatch)
        assertFalse(close.confirmDiscard)
    }

    @Test fun saveNeverAppearsOnLandingLibraryServiceOrMealDetailsPages() {
        for(page in listOf(null,"home","picker","services","mealHistory")) {
            for(draft in listOf(review,meal))assertNull(foodSaveAction(page,draft,false,false))
        }
    }

    @Test fun onlyTheVisibleReviewCanConfirmPendingResults() {
        for(page in listOf("photo","review"))assertEquals(FoodSaveAction.CONFIRM_BATCH,foodSaveAction(page,review,false,false))
        assertNull(foodSaveAction("editor",review,false,false))
        assertNull(foodSaveAction("photo",review.copy(batch=review.batch!!.copy(status="interrupted")),false,false))
        assertNull(foodSaveAction("review",FoodDraft(batch=FoodBatch(kind="manual",status="review",results=listOf(FoodItem()))),false,false))
    }

    @Test fun confirmedMealOnlySavesOnItsEditorWithoutAnUnfinishedBatch() {
        assertEquals(FoodSaveAction.SAVE_MEAL,foodSaveAction("editor",meal,false,false))
        assertNull(foodSaveAction("editor",meal.copy(batch=FoodBatch(photos=listOf("meal.jpg"))),false,false))
        assertNull(foodSaveAction("review",meal,false,false))
        assertNull(foodSaveAction("editor",FoodDraft(),false,false))
        assertNull(foodSaveAction("editor",null,false,false))
        assertNull(foodSaveAction("editor",meal,true,false))
        assertNull(foodSaveAction("photo",review,false,true))
    }
}
