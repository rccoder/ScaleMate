package app.scalemate.food

import org.junit.Assert.*
import org.junit.Test

class FoodAiLanguageTest {
    @Test fun chineseScriptAndRegionChooseTheExpectedWritingSystem() {
        assertTrue(FoodAiLanguage.instruction("zh-CN").contains("Simplified Chinese (zh-CN)"))
        assertTrue(FoodAiLanguage.instruction("zh-Hans-TW").contains("Simplified Chinese"))
        assertTrue(FoodAiLanguage.instruction("zh-Hant").contains("Traditional Chinese"))
        assertTrue(FoodAiLanguage.instruction("zh-TW").contains("Traditional Chinese"))
    }
    @Test fun explicitApplicationLanguageWinsOverProcessDefault() {
        val old=java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.CHINA)
            assertTrue(FoodAiLanguage.instruction("en-US").contains("English (en-US)"))
            java.util.Locale.setDefault(java.util.Locale.US)
            assertTrue(FoodAiLanguage.instruction("zh-CN").contains("Simplified Chinese"))
        }finally {java.util.Locale.setDefault(old)}
    }
    @Test fun localePolicyCoversAllTextButPreservesTheNumericContract() {
        val policy=FoodAiLanguage.instruction("en-GB")
        listOf("name, personalPortion, intakeSummary, assumptions, contextInterpretation, contextWarning, eatingAdvice, nextTimeAdvice, positiveFeedback, mealAdvice.healthiness, mealAdvice.balance, mealAdvice.nextTime and mealAdvice.note","MUST NOT change the target language","calculationMode personal","kcal/g").forEach {assertTrue(policy.contains(it))}
        assertTrue(FoodAiLanguage.instruction("und").contains("English (en)"))
    }
    @Test fun followSystemUsesSystemLanguageUnlessApplicationWasExplicitlyOverridden() {
        assertEquals("zh-CN",FoodAiLanguage.outputLanguage("","zh-CN,en-US"))
        assertEquals("en-US",FoodAiLanguage.outputLanguage("en-US","zh-CN"))
        assertEquals("zh-CN",FoodAiLanguage.outputLanguage("zh-CN","en-US"))
        assertEquals("en-US",FoodAiLanguage.outputLanguage("und","en-US"))
    }

}
