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
) {
    /** Resolve old defaults without changing stored bindings, history, or user-defined names. */
    fun displayName(storedName: String, localizedName: String): String =
        if (storedName.isBlank() || storedName == id || storedName in legacyDefaultNames) localizedName else storedName
}

object ScaleCatalog {
    val yunmai3Se = ScaleModel(
        id = "yunmai-smart-body-fat-scale-3-se",
        manufacturer = "YUNMAI",
        internalModel = "YMBS-M263",
        adapterId = YunmaiM263.ADAPTER,
        displayNameResource = R.string.model_yunmai_smart_body_fat_scale_3_se,
        legacyDefaultNames = setOf("云麦 YMBS-M263", "YUNMAI YMBS-M263", "YUNMAI M263"),
    )
    val supported: List<ScaleModel> = listOf(yunmai3Se)
    fun forAdapter(adapterId: String): ScaleModel? = supported.find { it.adapterId == adapterId }
    fun forInternalModel(internalModel: String): ScaleModel? = supported.find { it.internalModel == internalModel }
}
