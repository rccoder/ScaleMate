package app.scalemate.food

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodImageBudgetTest {
    @Test fun uploadImagesUseCompactResolutionAndQuality() {
        assertEquals(1024,FoodImages.MAX_UPLOAD_EDGE_PX)
        assertEquals(70,FoodImages.UPLOAD_JPEG_QUALITY)
    }

    @Test fun uploadBudgetAccountsForBase64Expansion() {
        val encoded=FoodImages.encodedRequestApproximation(FoodImages.MAX_UPLOAD_BYTES)
        assertTrue("Base64 expands the upload by roughly one third",encoded>=10_000_000)
        assertEquals(4,FoodImages.encodedRequestApproximation(1))
    }

    @Test fun sixImagesCannotReachTheOldDecodedLimit() {
        assertTrue(FoodImages.MAX_IMAGE_BYTES*FoodImages.MAX_PHOTOS>FoodImages.MAX_UPLOAD_BYTES)
    }

    @Test fun oldSixImageLimitExplainsOversizedBase64Requests() {
        val oldDecodedLimit=4_000_000L*FoodImages.MAX_PHOTOS
        assertTrue(FoodImages.encodedRequestApproximation(oldDecodedLimit)>=32_000_000)
    }

    @Test fun currentSixImageLimitStaysBelowTwelveMegabytesAfterBase64() {
        val currentEncodedLimit=FoodImages.encodedRequestApproximation(FoodImages.MAX_UPLOAD_BYTES)
        assertTrue(currentEncodedLimit<12_000_000)
    }

    @Test fun recognitionKeepsAUsableTimeoutBudget() {
        assertTrue(AiClient.RESPONSE_TIMEOUT_MS>=90_000)
    }
}
