package app.scalemate.core

import org.junit.Assert.*
import org.junit.Test

class ScaleCatalogTest {
    private val model = ScaleCatalog.yunmai3Se
    @Test fun oldDefaultNamesFollowCurrentLocale() {
        for (oldName in model.legacyDefaultNames) {
            assertEquals("云麦智能体脂秤 3 SE", model.displayName(oldName, "云麦智能体脂秤 3 SE"))
            assertEquals("YUNMAI Smart Body Fat Scale 3 SE", model.displayName(oldName, "YUNMAI Smart Body Fat Scale 3 SE"))
        }
    }
    @Test fun customDeviceAndRecordNamesArePreserved() {
        for (name in listOf("卧室的秤", "Bathroom scale", "My YMBS-M263")) {
            assertEquals(name, model.displayName(name, "云麦智能体脂秤 3 SE"))
        }
    }
    @Test fun defaultIdentityIsLocalizedAndUnsupportedModelsStayUnsupported() {
        assertEquals("中文名", model.displayName(model.id, "中文名"))
        assertEquals("English name", model.displayName("", "English name"))
        assertSame(model, ScaleCatalog.forAdapter(YunmaiM263.ADAPTER))
        assertSame(model, ScaleCatalog.forInternalModel("YMBS-M263"))
        assertNull(ScaleCatalog.forInternalModel("YMBS-M268"))
        assertNull(ScaleCatalog.forAdapter("unknown"))
    }
}
