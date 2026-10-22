package app.scalemate.food

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodImageBudgetTest {
    @Test fun uploadBudgetAccountsForBase64Expansion() {
        val encoded=FoodImages.encodedRequestApproximation(FoodImages.MAX_UPLOAD_BYTES)
        assertTrue("Base64 expands the upload by roughly one third",encoded>=12_000_000)
        assertEquals(4,FoodImages.encodedRequestApproximation(1))
    }

    @Test fun sixImagesCannotReachTheOldDecodedLimit() {
        assertTrue(FoodImages.MAX_IMAGE_BYTES*FoodImages.MAX_PHOTOS<=FoodImages.MAX_UPLOAD_BYTES)
    }

    @Test fun recognitionKeepsAUsableTimeoutBudget() {
        assertTrue(AiClient.RESPONSE_TIMEOUT_MS>=90_000)
    }
}
