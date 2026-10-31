package app.scalemate.core

import androidx.annotation.StringRes
import app.scalemate.R

/** Product names are localized resources; protocol identity stays independent of display names. */
data class ScaleModel(
    val id: String,
    val manufacturer: String,
    val internalModel: String,
    val adapterId: String,
    @param:StringRes val displayNameResource: Int,
    val legacyDefaultNames: Set<String>,
    val connection: ScaleConnection = ScaleConnection.ADVERTISEMENT,
) {
    /** Resolve old defaults without changing stored bindings, history, or user-defined names. */
    fun displayName(storedName: String, localizedName: String): String =
        if (storedName.isBlank() || storedName == id || storedName in legacyDefaultNames) localizedName else storedName
}

object ScaleCatalog {
    const val defaultModelId = "yunmai-smart-body-fat-scale-3-se"

    val yunmai3Se = ScaleModel(
        id = defaultModelId,
        manufacturer = "YUNMAI",
        internalModel = "YMBS-M263",
        adapterId = YunmaiM263.ADAPTER,
        displayNameResource = R.string.model_yunmai_smart_body_fat_scale_3_se,
        legacyDefaultNames = setOf("云麦 YMBS-M263", "YUNMAI YMBS-M263", "YUNMAI M263"),
    )
    val yunmaiMiniGatt = ScaleModel(
        id = "yunmai-mini-gatt-body-fat-scale",
        manufacturer = "YUNMAI",
        internalModel = "YUNMAI-IS*/SIGNAL",
        adapterId = YunmaiGattProtocol.ADAPTER,
        displayNameResource = R.string.model_yunmai_mini_gatt_body_fat_scale,
        legacyDefaultNames = emptySet(),
        connection = ScaleConnection.GATT,
    )
    val yunmaiSeGatt = ScaleModel(
        id = "yunmai-se-gatt-body-fat-scale",
        manufacturer = "YUNMAI",
        internalModel = "YUNMAI-ISSE",
        adapterId = YunmaiGattProtocol.ADAPTER,
        displayNameResource = R.string.model_yunmai_se_gatt_body_fat_scale,
        legacyDefaultNames = emptySet(),
        connection = ScaleConnection.GATT,
    )
    val yunmaiX = ScaleModel(
        id = "yunmai-x-smart-body-fat-scale",
        manufacturer = "YUNMAI",
        internalModel = "YMBS-M268",
        adapterId = YunmaiXBroadcast.ADAPTER,
        displayNameResource = R.string.model_yunmai_x_smart_body_fat_scale,
        legacyDefaultNames = emptySet(),
    )
    val yunmaiMini2S = ScaleModel(
        id = "yunmai-mini-2s-smart-body-fat-scale",
        manufacturer = "YUNMAI",
        internalModel = "M1827CH",
        adapterId = YunmaiXBroadcast.ADAPTER,
        displayNameResource = R.string.model_yunmai_mini_2s_smart_body_fat_scale,
        legacyDefaultNames = emptySet(),
    )
    val picoocMiniLite = ScaleModel(
        id = "picooc-mini-lite-body-fat-scale",
        manufacturer = "PICOOC",
        internalModel = "PICOOC-L",
        adapterId = PicoocMiniLiteBroadcast.ADAPTER,
        displayNameResource = R.string.model_picooc_mini_lite_body_fat_scale,
        legacyDefaultNames = emptySet(),
    )
    val etekcityFit8S = ScaleModel(
        id = "etekcity-fit-8s-body-fat-scale",
        manufacturer = "Etekcity",
        internalModel = "Fit 8S",
        adapterId = EtekcityFit8SBroadcast.ADAPTER,
        displayNameResource = R.string.model_etekcity_fit_8s_body_fat_scale,
        legacyDefaultNames = emptySet(),
    )
    val fitIndexFt26rW = ScaleModel(
        id = "fitindex-ft-26r-w-smart-scale",
        manufacturer = "FITINDEX",
        internalModel = "FT-26R-W",
        adapterId = FitIndexFt26rBroadcast.ADAPTER,
        displayNameResource = R.string.model_fitindex_ft_26r_w_smart_scale,
        legacyDefaultNames = emptySet(),
    )
    val okokV20 = ScaleModel(
        id = "okok-v20-smart-scale",
        manufacturer = "OKOK",
        internalModel = "V20",
        adapterId = OkOkBroadcast.ADAPTER,
        displayNameResource = R.string.model_okok_v20_smart_scale,
        legacyDefaultNames = emptySet(),
    )
    val okokV11 = ScaleModel(
        id = "okok-v11-smart-scale",
        manufacturer = "OKOK",
        internalModel = "V11",
        adapterId = OkOkBroadcast.ADAPTER,
        displayNameResource = R.string.model_okok_v11_smart_scale,
        legacyDefaultNames = emptySet(),
    )
    val okokVf0 = ScaleModel(
        id = "okok-vf0-smart-scale",
        manufacturer = "OKOK",
        internalModel = "VF0",
        adapterId = OkOkBroadcast.ADAPTER,
        displayNameResource = R.string.model_okok_vf0_smart_scale,
        legacyDefaultNames = emptySet(),
    )
    val xiaomiWeightScale2 = ScaleModel(
        id = "xiaomi-mi-smart-scale-2",
        manufacturer = "Xiaomi",
        internalModel = "XMTZC04HM",
        adapterId = XiaomiGattProtocol.ADAPTER,
        displayNameResource = R.string.model_xiaomi_mi_smart_scale_2,
        legacyDefaultNames = emptySet(),
        connection = ScaleConnection.GATT,
    )
    val xiaomiBodyScale2 = ScaleModel(
        id = "xiaomi-mi-body-composition-scale-2",
        manufacturer = "Xiaomi",
        internalModel = "XMTZC05HM",
        adapterId = XiaomiGattProtocol.ADAPTER,
        displayNameResource = R.string.model_xiaomi_mi_body_composition_scale_2,
        legacyDefaultNames = emptySet(),
        connection = ScaleConnection.GATT,
    )
    val picoocGatt = ScaleModel(
        id = "picooc-connection-body-fat-scale",
        manufacturer = "PICOOC",
        internalModel = "PICOOC / Latin series",
        adapterId = PicoocGattProtocol.ADAPTER,
        displayNameResource = R.string.model_picooc_connection_body_fat_scale,
        legacyDefaultNames = emptySet(),
        connection = ScaleConnection.GATT,
    )
    val honorBodyFatScaleAh100 = ScaleModel(
        id = "honor-body-fat-scale-ah100",
        manufacturer = "HONOR",
        internalModel = "AH100 / CH100",
        adapterId = HuaweiAhCh100GattProtocol.ADAPTER,
        displayNameResource = R.string.model_honor_body_fat_scale_ah100,
        legacyDefaultNames = emptySet(),
        connection = ScaleConnection.GATT,
    )
    val realmeSmartScale = ScaleModel(
        id = "realme-smart-scale-rmh2011",
        manufacturer = "realme",
        internalModel = "RMH2011",
        adapterId = RealmeGattProtocol.ADAPTER,
        displayNameResource = R.string.model_realme_smart_scale_rmh2011,
        legacyDefaultNames = emptySet(),
        connection = ScaleConnection.GATT,
    )
    val supported: List<ScaleModel> =
        listOf(
            yunmai3Se,
            yunmaiMiniGatt,
            yunmaiSeGatt,
            yunmaiX,
            yunmaiMini2S,
            picoocMiniLite,
            picoocGatt,
            xiaomiWeightScale2,
            xiaomiBodyScale2,
            honorBodyFatScaleAh100,
            realmeSmartScale,
            etekcityFit8S,
            fitIndexFt26rW,
            okokV20,
            okokV11,
            okokVf0,
        )

    fun forId(id: String?): ScaleModel? = supported.find { it.id == id }
    fun forAdapter(adapterId: String): ScaleModel? = supported.find { it.adapterId == adapterId }
    fun forInternalModel(internalModel: String): ScaleModel? = supported.find { it.internalModel == internalModel }
    fun forStoredName(name: String): ScaleModel? = supported.find { it.id == name || name in it.legacyDefaultNames }
}
