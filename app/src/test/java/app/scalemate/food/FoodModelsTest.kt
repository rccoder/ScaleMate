package app.scalemate.food

import org.junit.Assert.*
import org.junit.Test

class FoodModelsTest {
    @Test fun emptySuggestionsAreHiddenAndFeedbackSurvivesReuse() {
        val empty=FoodItem(name="米饭",base=Nutrients(100.0),eatingAdvice="  ",nextTimeAdvice="\n")
        assertFalse(empty.hasEatingSuggestions)
        assertTrue(empty.copy(eatingAdvice="酱汁按需蘸取").hasEatingSuggestions)
        assertTrue(empty.copy(nextTimeAdvice="下次可搭配蔬菜").hasEatingSuggestions)
        val feedback=empty.copy(positiveFeedback="这份搭配包含主食与蛋白质。")
        assertTrue(feedback.hasEatingSuggestions)
        assertEquals(feedback.positiveFeedback,feedback.reuse().positiveFeedback)
    }
    @Test fun estimateSnapshotKeepsSharingSeparateFromNormalizedIntake() {
        val own=FoodEstimateContext(2,1.0/3,"own",300.0,Nutrients(600.0))
        assertEquals(100.0,own.wholeNutrients.kcal!!*own.factor,0.001)
        assertEquals(200.0,own.copy(fractionBasis="whole").wholeNutrients.kcal!!*own.copy(fractionBasis="whole").factor,0.001)
        val food=FoodItem(name="米饭",base=Nutrients(100.0),estimateContext=own)
        assertEquals(100.0,food.nutrients.kcal!!,0.001)
        assertEquals(own,food.reuse().estimateContext)
        assertEquals(own,food.copy(base=Nutrients(80.0)).estimateContext)
    }
    @Test fun sharingAndFractionHaveExplicitDenominator() {
        val rice=FoodItem(name="rice",base=Nutrients(600.0,12.0,120.0,4.0),shareCount=2,fraction=1.0/3)
        assertEquals(200.0,rice.nutrients.kcal!!,0.001)
        assertEquals(100.0,rice.copy(basis="own").nutrients.kcal!!,0.001)
    }
    @Test fun reuseNeverAppliesOldSharingTwice() {
        val f=FoodItem(name="rice",grams=300.0,base=Nutrients(600.0),quantity=2.0,shareCount=2,fraction=0.5,basis="own")
        val reused=f.reuse()
        assertNotEquals(f.id,reused.id);assertEquals(f.nutrients,reused.nutrients)
        assertEquals(150.0,reused.grams!!,0.001)
        assertEquals(600.0,reused.copy(quantity=2.0).nutrients.kcal!!,0.001)
    }
    @Test fun missingNutrientsAreNotZeros() {
        val total=Nutrients.sum(listOf(Nutrients(100.0,2.0),Nutrients(200.0)))
        assertEquals(300.0,total.kcal!!,0.001);assertNull(total.protein);assertNull(Nutrients.sum(emptyList()).kcal)
    }
    @Test fun invalidNumbersAndUnconfirmedEstimatesCannotSave() {
        assertFalse(FoodItem(name="x",base=Nutrients(Double.NaN)).valid())
        assertFalse(FoodItem(name="x",base=Nutrients(-1.0)).valid())
        assertFalse(FoodItem(name="x",base=Nutrients(100.0),fraction=Double.POSITIVE_INFINITY).valid())
        assertFalse(FoodItem(name="x",base=Nutrients(100.0),needsPortionReview=true).valid())
        assertFalse(FoodItem(name="x",base=Nutrients(100.0),quantity=0.0).valid())
    }
    @Test fun modelListEndpointsKeepProviderPrefixesAndAcceptFullInferencePaths() {
        assertEquals("https://example.com/v1/models",AiEndpoint.modelsUrl("https://example.com/v1/","openai"))
        assertEquals("https://example.com/proxy/v1/models",AiEndpoint.modelsUrl("https://example.com/proxy/v1/chat/completions","openai"))
        listOf("https://example.com","https://example.com/v1/","https://example.com/v1/messages").forEach {assertEquals("https://example.com/v1/models",AiEndpoint.modelsUrl(it,"anthropic"))}
        try {AiEndpoint.modelsUrl("https://example.com/v1?key=secret","openai");fail("Query credentials accepted")}catch(_:IllegalArgumentException){}
    }
    @Test fun anthropicEndpointsHandleRootVersionAndFullPath() {
        listOf("https://example.com","https://example.com/v1/","https://example.com/v1/messages").forEach {
            assertEquals("https://example.com/v1/messages",AiEndpoint.requestUrl(it,"anthropic"))
        }
        assertEquals("https://example.com/proxy/v1/messages",AiEndpoint.requestUrl("https://example.com/proxy","anthropic"))
        try {AiEndpoint.requestUrl("https://example.com","unknown");fail("Unknown format accepted")}catch(_:IllegalArgumentException){}
    }
    @Test fun remoteCredentialsCannotLeakThroughHttpOrRedirectTargets() {
        assertEquals("https://example.com/v1/chat/completions",AiEndpoint.completionUrl("https://example.com/v1/"))
        assertEquals("http://192.168.2.51:3425/v1/chat/completions",AiEndpoint.completionUrl("http://192.168.2.51:3425/v1"))
        listOf("http://example.com/v1","https://user:password@example.com/v1","https://example.com/v1?key=secret","file:///tmp/key","http://192.168.300.1/v1").forEach {url->
            try {AiEndpoint.completionUrl(url);fail("Unsafe endpoint accepted")}catch(_:IllegalArgumentException){}
        }
    }
}
