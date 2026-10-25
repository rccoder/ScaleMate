package app.scalemate.food

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FoodJsonUsageTest {
    @Test fun usageAcceptsOpenAiAndAnthropicFieldNames() {
        val openAi=FoodJson.usage("""{"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""")
        assertEquals(10L,openAi.inputTokens);assertEquals(5L,openAi.outputTokens);assertEquals(15L,openAi.totalTokens)
        val anthropic=FoodJson.usage("""{"usage":{"input_tokens":20,"output_tokens":8}}""")
        assertEquals(20L,anthropic.inputTokens);assertEquals(8L,anthropic.outputTokens);assertNull(anthropic.totalTokens)
        assertFalse(FoodJson.usage("""{"usage":null}""").hasContent)
        assertFalse(FoodJson.usage("not json").hasContent)
    }

    @Test fun mealAdviceKeepsOnlySupportedContentAndSurvivesMealJson() {
        val advice=MealAdvice("主食和蛋白质为主","搭配蔬菜","下次减少油炸","蔬菜不可见")
        val meal=Meal(items=listOf(FoodItem(name="咖喱猪排饭",base=Nutrients(650.0))),advice=advice,usage=AiUsage(100,20,120),analysisMs=9_876)
        assertEquals(meal,FoodJson.meal(FoodJson.meal(meal)))
        assertFalse(MealAdvice(note="仅说明不确定").hasContent)
        assertFalse(MealAdvice().hasContent)
    }

    @Test fun emptyRecognitionIsDistinctFromMalformedJson() {
        assertThrows(FoodJson.EmptyRecognition::class.java) {
            FoodJson.recognition("""{"calculationMode":"personal","foods":[]}""",1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FoodJson.recognition("""{"foods":[]}""",1)
        }
    }

    @Test fun recognitionAcceptsCodeFenceOrBriefSurroundingText() {
        val payload="""{"calculationMode":"personal","foods":[{"name":"米饭","personalNutrients":{"kcal":200}}]}"""
        assertEquals("米饭",FoodJson.recognition("```json\n$payload\n```",1).items.single().name)
        assertEquals("米饭",FoodJson.recognition("Here is the result:\n$payload\nDone.",1).items.single().name)
    }
}
