package app.scalemate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaleCatalogTest {
    @Test fun catalogContainsAdvertisingAndGattFamiliesWithoutDuplicateProducts() {
        assertEquals(
            listOf(
                ScaleCatalog.yunmai3Se,
                ScaleCatalog.yunmaiMiniGatt,
                ScaleCatalog.yunmaiSeGatt,
                ScaleCatalog.yunmaiX,
                ScaleCatalog.yunmaiMini2S,
                ScaleCatalog.picoocMiniLite,
                ScaleCatalog.picoocGatt,
                ScaleCatalog.xiaomiWeightScale2,
                ScaleCatalog.xiaomiBodyScale2,
                ScaleCatalog.honorBodyFatScaleAh100,
                ScaleCatalog.realmeSmartScale,
                ScaleCatalog.etekcityFit8S,
                ScaleCatalog.fitIndexFt26rW,
                ScaleCatalog.okokV20,
                ScaleCatalog.okokV11,
                ScaleCatalog.okokVf0,
            ),
            ScaleCatalog.supported,
        )
        assertEquals(ScaleCatalog.supported.size, ScaleCatalog.supported.map { it.id }.distinct().size)
        assertEquals(ScaleCatalog.supported.size, ScaleCatalog.supported.map { it.displayNameResource }.distinct().size)
        assertTrue(ScaleCatalog.supported.all { it.id.isNotBlank() && it.adapterId.isNotBlank() })
    }

    @Test fun displayNamesUseLocalizedProductNamesInsteadOfStoredIds() {
        for (model in ScaleCatalog.supported) {
            assertEquals("中文 ${model.id}", model.displayName(model.id, "中文 ${model.id}"))
            assertEquals("English ${model.id}", model.displayName("", "English ${model.id}"))
        }
    }

    @Test fun legacyDefaultNamesFollowCurrentLocale() {
        for (model in ScaleCatalog.supported) {
            for (oldName in model.legacyDefaultNames) {
                assertEquals("本地化名称", model.displayName(oldName, "本地化名称"))
            }
        }
        assertEquals(
            "云麦智能体脂秤 3 SE",
            ScaleCatalog.yunmai3Se.displayName("云麦 YMBS-M263", "云麦智能体脂秤 3 SE"),
        )
        assertEquals(
            "YUNMAI Smart Body Fat Scale 3 SE",
            ScaleCatalog.yunmai3Se.displayName("YUNMAI M263", "YUNMAI Smart Body Fat Scale 3 SE"),
        )
    }

    @Test fun customDeviceNamesArePreserved() {
        for (name in listOf("卧室的秤", "Bathroom scale", "My YMBS-M263")) {
            assertEquals(name, ScaleCatalog.yunmai3Se.displayName(name, "云麦智能体脂秤 3 SE"))
        }
    }

    @Test fun catalogResolvesIdsInternalModelsAndStoredNamesWithoutConflatingThem() {
        assertSame(ScaleCatalog.yunmai3Se, ScaleCatalog.forId(ScaleCatalog.defaultModelId))
        assertSame(ScaleCatalog.yunmaiX, ScaleCatalog.forInternalModel("YMBS-M268"))
        assertSame(ScaleCatalog.yunmaiMini2S, ScaleCatalog.forInternalModel("M1827CH"))
        assertSame(ScaleCatalog.picoocMiniLite, ScaleCatalog.forAdapter(PicoocMiniLiteBroadcast.ADAPTER))
        assertSame(ScaleCatalog.picoocGatt, ScaleCatalog.forAdapter(PicoocGattProtocol.ADAPTER))
        assertSame(ScaleCatalog.honorBodyFatScaleAh100, ScaleCatalog.forAdapter(HuaweiAhCh100GattProtocol.ADAPTER))
        assertSame(ScaleCatalog.realmeSmartScale, ScaleCatalog.forAdapter(RealmeGattProtocol.ADAPTER))
        assertSame(ScaleCatalog.xiaomiWeightScale2, ScaleCatalog.forAdapter(XiaomiGattProtocol.ADAPTER))
        assertSame(ScaleCatalog.yunmai3Se, ScaleCatalog.forStoredName("YUNMAI YMBS-M263"))
        for (model in ScaleCatalog.supported) assertSame(model, ScaleCatalog.forStoredName(model.id))
        assertNull(ScaleCatalog.forId(null))
        assertNull(ScaleCatalog.forId("unknown"))
        assertNull(ScaleCatalog.forInternalModel("unknown"))
        assertNull(ScaleCatalog.forAdapter("unknown"))
        assertNull(ScaleCatalog.forStoredName("My custom scale"))
        assertNotNull(ScaleCatalog.forId(ScaleCatalog.fitIndexFt26rW.id))
    }

    @Test fun sharedAdapterFamiliesKeepDistinctProductModels() {
        assertSame(ScaleCatalog.yunmaiX, ScaleCatalog.forAdapter(YunmaiXBroadcast.ADAPTER))
        assertSame(ScaleCatalog.okokV20, ScaleCatalog.forAdapter(OkOkBroadcast.ADAPTER))
        assertEquals(ScaleCatalog.yunmaiX.adapterId, ScaleCatalog.yunmaiMini2S.adapterId)
        assertEquals(ScaleCatalog.okokV20.adapterId, ScaleCatalog.okokV11.adapterId)
        assertEquals(ScaleCatalog.okokV20.adapterId, ScaleCatalog.okokVf0.adapterId)
        assertEquals(ScaleCatalog.yunmaiMiniGatt.adapterId, ScaleCatalog.yunmaiSeGatt.adapterId)
        assertEquals(ScaleCatalog.xiaomiWeightScale2.adapterId, ScaleCatalog.xiaomiBodyScale2.adapterId)
    }
}
