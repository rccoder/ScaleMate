package app.scalemate.health

data class HealthKind(val id:String,val permissionSuffix:String,val english:String,val chinese:String,val sensitive:Boolean=false,val medicalType:String="") {
    val permission get()="android.permission.health.READ_$permissionSuffix"
    val className get()="android.health.connect.datatypes.${if(id=="ExerciseRoute")"ExerciseSession" else id}Record"
    fun label(chineseLanguage:Boolean)=if(chineseLanguage)chinese else english
}
enum class HealthPrivacyGroup(val english:String,val chinese:String) {
    ACTIVITY("Everyday activity","日常活动"),
    BODY("Body and nutrition","身体与营养"),
    CLINICAL("Sensitive or clinical records","敏感或临床记录")
}

object HealthCatalog {
    const val HISTORY="android.permission.health.READ_HEALTH_DATA_HISTORY"
    const val BACKGROUND="android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    val kinds=listOf(
        HealthKind("SleepSession", "SLEEP", "Sleep", "\u7761\u7720", sensitive=false),
        HealthKind("HeartRate", "HEART_RATE", "Heart rate", "\u5fc3\u7387", sensitive=false),
        HealthKind("HeartRateVariabilityRmssd", "HEART_RATE_VARIABILITY", "HRV (RMSSD)", "\u5fc3\u7387\u53d8\u5f02\u6027\uff08RMSSD\uff09", sensitive=false),
        HealthKind("Steps", "STEPS", "Steps", "\u6b65\u6570", sensitive=false),
        HealthKind("ExerciseSession", "EXERCISE", "Exercise", "\u8fd0\u52a8", sensitive=false),
        HealthKind("Weight", "WEIGHT", "Weight", "\u4f53\u91cd", sensitive=false),
        HealthKind("Nutrition", "NUTRITION", "Nutrition", "\u8425\u517b\u6444\u5165", sensitive=false),
        HealthKind("ActiveCaloriesBurned", "ACTIVE_CALORIES_BURNED", "Active energy", "\u6d3b\u52a8\u6d88\u8017", sensitive=false),
        HealthKind("TotalCaloriesBurned", "TOTAL_CALORIES_BURNED", "Total energy burned", "\u603b\u80fd\u91cf\u6d88\u8017", sensitive=false),
        HealthKind("Distance", "DISTANCE", "Distance", "\u8ddd\u79bb", sensitive=false),
        HealthKind("ElevationGained", "ELEVATION_GAINED", "Elevation gained", "\u7d2f\u8ba1\u722c\u5347", sensitive=false),
        HealthKind("FloorsClimbed", "FLOORS_CLIMBED", "Floors climbed", "\u722c\u697c\u5c42\u6570", sensitive=false),
        HealthKind("Speed", "SPEED", "Speed", "\u901f\u5ea6", sensitive=false),
        HealthKind("StepsCadence", "STEPS", "Step cadence", "\u6b65\u9891", sensitive=false),
        HealthKind("CyclingPedalingCadence", "EXERCISE", "Cycling cadence", "\u9a91\u884c\u8e0f\u9891", sensitive=false),
        HealthKind("Power", "POWER", "Power", "\u529f\u7387", sensitive=false),
        HealthKind("Vo2Max", "VO2_MAX", "VO2 max", "\u6700\u5927\u6444\u6c27\u91cf", sensitive=false),
        HealthKind("WheelchairPushes", "WHEELCHAIR_PUSHES", "Wheelchair pushes", "\u8f6e\u6905\u63a8\u52a8", sensitive=false),
        HealthKind("BasalMetabolicRate", "BASAL_METABOLIC_RATE", "Basal metabolic rate", "\u57fa\u7840\u4ee3\u8c22\u7387", sensitive=false),
        HealthKind("Height", "HEIGHT", "Height", "\u8eab\u9ad8", sensitive=false),
        HealthKind("BodyFat", "BODY_FAT", "Body fat", "\u4f53\u8102\u7387", sensitive=false),
        HealthKind("BodyWaterMass", "BODY_WATER_MASS", "Body water mass", "\u8eab\u4f53\u6c34\u5206\u8d28\u91cf", sensitive=false),
        HealthKind("BoneMass", "BONE_MASS", "Bone mass", "\u9aa8\u91cf", sensitive=false),
        HealthKind("LeanBodyMass", "LEAN_BODY_MASS", "Lean body mass", "\u7626\u4f53\u91cd", sensitive=false),
        HealthKind("Hydration", "HYDRATION", "Hydration", "\u996e\u6c34", sensitive=false),
        HealthKind("RestingHeartRate", "RESTING_HEART_RATE", "Resting heart rate", "\u9759\u606f\u5fc3\u7387", sensitive=false),
        HealthKind("OxygenSaturation", "OXYGEN_SATURATION", "Oxygen saturation", "\u8840\u6c27\u9971\u548c\u5ea6", sensitive=false),
        HealthKind("RespiratoryRate", "RESPIRATORY_RATE", "Respiratory rate", "\u547c\u5438\u9891\u7387", sensitive=false),
        HealthKind("BodyTemperature", "BODY_TEMPERATURE", "Body temperature", "\u4f53\u6e29", sensitive=false),
        HealthKind("BasalBodyTemperature", "BASAL_BODY_TEMPERATURE", "Basal body temperature", "\u57fa\u7840\u4f53\u6e29", sensitive=false),
        HealthKind("BloodPressure", "BLOOD_PRESSURE", "Blood pressure", "\u8840\u538b", sensitive=false),
        HealthKind("BloodGlucose", "BLOOD_GLUCOSE", "Blood glucose", "\u8840\u7cd6", sensitive=false),
        HealthKind("MenstruationFlow", "MENSTRUATION", "Menstrual flow", "\u6708\u7ecf\u6d41\u91cf", sensitive=true),
        HealthKind("MenstruationPeriod", "MENSTRUATION", "Menstrual period", "\u7ecf\u671f", sensitive=true),
        HealthKind("IntermenstrualBleeding", "INTERMENSTRUAL_BLEEDING", "Intermenstrual bleeding", "\u7ecf\u95f4\u671f\u51fa\u8840", sensitive=true),
        HealthKind("CervicalMucus", "CERVICAL_MUCUS", "Cervical mucus", "\u5bab\u9888\u9ecf\u6db2", sensitive=true),
        HealthKind("OvulationTest", "OVULATION_TEST", "Ovulation test", "\u6392\u5375\u6d4b\u8bd5", sensitive=true),
        HealthKind("SexualActivity", "SEXUAL_ACTIVITY", "Sexual activity", "\u6027\u6d3b\u52a8", sensitive=true),
        HealthKind("SkinTemperature", "SKIN_TEMPERATURE", "Skin temperature", "\u76ae\u80a4\u6e29\u5ea6", sensitive=false),
        HealthKind("MindfulnessSession", "MINDFULNESS", "Mindfulness", "\u6b63\u5ff5", sensitive=false),
        HealthKind("PlannedExerciseSession", "PLANNED_EXERCISE", "Planned exercise", "\u8fd0\u52a8\u8ba1\u5212", sensitive=false),
        HealthKind("ActivityIntensity", "ACTIVITY_INTENSITY", "Activity intensity", "\u6d3b\u52a8\u5f3a\u5ea6", sensitive=false),
        HealthKind("ExerciseRoute", "EXERCISE_ROUTES", "Exercise route", "\u8fd0\u52a8\u8def\u7ebf", sensitive=true),
        HealthKind("Medical_ALLERGIES_INTOLERANCES", "MEDICAL_DATA_ALLERGIES_INTOLERANCES", "Allergies and intolerances", "\u8fc7\u654f\u4e0e\u4e0d\u8010\u53d7", sensitive=true, medicalType="ALLERGIES_INTOLERANCES"),
        HealthKind("Medical_CONDITIONS", "MEDICAL_DATA_CONDITIONS", "Conditions", "\u5065\u5eb7\u72b6\u51b5", sensitive=true, medicalType="CONDITIONS"),
        HealthKind("Medical_LABORATORY_RESULTS", "MEDICAL_DATA_LABORATORY_RESULTS", "Laboratory results", "\u68c0\u9a8c\u7ed3\u679c", sensitive=true, medicalType="LABORATORY_RESULTS"),
        HealthKind("Medical_MEDICATIONS", "MEDICAL_DATA_MEDICATIONS", "Medications", "\u7528\u836f\u8bb0\u5f55", sensitive=true, medicalType="MEDICATIONS"),
        HealthKind("Medical_PERSONAL_DETAILS", "MEDICAL_DATA_PERSONAL_DETAILS", "Personal details", "\u4e2a\u4eba\u8d44\u6599", sensitive=true, medicalType="PERSONAL_DETAILS"),
        HealthKind("Medical_PRACTITIONER_DETAILS", "MEDICAL_DATA_PRACTITIONER_DETAILS", "Practitioner details", "\u533b\u62a4\u4eba\u5458\u8d44\u6599", sensitive=true, medicalType="PRACTITIONER_DETAILS"),
        HealthKind("Medical_PREGNANCY", "MEDICAL_DATA_PREGNANCY", "Pregnancy", "\u5b55\u671f", sensitive=true, medicalType="PREGNANCY"),
        HealthKind("Medical_PROCEDURES", "MEDICAL_DATA_PROCEDURES", "Procedures", "\u8bca\u7597\u64cd\u4f5c", sensitive=true, medicalType="PROCEDURES"),
        HealthKind("Medical_SOCIAL_HISTORY", "MEDICAL_DATA_SOCIAL_HISTORY", "Social history", "\u793e\u4f1a\u53f2", sensitive=true, medicalType="SOCIAL_HISTORY"),
        HealthKind("Medical_VACCINES", "MEDICAL_DATA_VACCINES", "Vaccines", "\u75ab\u82d7", sensitive=true, medicalType="VACCINES"),
        HealthKind("Medical_VISITS", "MEDICAL_DATA_VISITS", "Visits", "\u5c31\u8bca", sensitive=true, medicalType="VISITS"),
        HealthKind("Medical_VITAL_SIGNS", "MEDICAL_DATA_VITAL_SIGNS", "Clinical vital signs", "\u4e34\u5e8a\u751f\u547d\u4f53\u5f81", sensitive=true, medicalType="VITAL_SIGNS"),
    )
    val defaults=kinds.filterNot {it.sensitive}.map {it.id}.toSet()
    val common=listOf("SleepSession","HeartRate","HeartRateVariabilityRmssd","Steps","ExerciseSession","Weight","Nutrition")
    fun kind(id:String)=kinds.single {it.id==id}
    val clinical=setOf(
        "Medical_ALLERGIES_INTOLERANCES","Medical_CONDITIONS","Medical_LABORATORY_RESULTS","Medical_MEDICATIONS",
        "Medical_PERSONAL_DETAILS","Medical_PRACTITIONER_DETAILS","Medical_PREGNANCY","Medical_PROCEDURES",
        "Medical_SOCIAL_HISTORY","Medical_VACCINES","Medical_VISITS","Medical_VITAL_SIGNS"
    )
    fun privacyGroup(kind:HealthKind)=when {
        kind.sensitive||kind.id in clinical->HealthPrivacyGroup.CLINICAL
        kind.id in setOf("Weight","Nutrition","Hydration","BodyFat","BodyWaterMass","BoneMass","LeanBodyMass","Height","BasalMetabolicRate","BloodPressure","BloodGlucose","OxygenSaturation","RespiratoryRate","BodyTemperature","BasalBodyTemperature","SkinTemperature","RestingHeartRate","Vo2Max")->HealthPrivacyGroup.BODY
        else->HealthPrivacyGroup.ACTIVITY
    }
}
