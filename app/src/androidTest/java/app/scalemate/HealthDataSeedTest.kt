package app.scalemate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.scalemate.data.BoundScale
import app.scalemate.data.Measurement
import app.scalemate.data.Store
import app.scalemate.food.FoodItem
import app.scalemate.food.FoodStore
import app.scalemate.food.Meal
import app.scalemate.food.MealAdvice
import app.scalemate.food.Nutrients
import app.scalemate.health.HealthStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One-off synthetic data for emulator acceptance. This is intentionally not a reusable
 * production fixture: names stay realistic and all identifiers use an isolated seed prefix.
 */
@RunWith(AndroidJUnit4::class)
class HealthDataSeedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val zone = ZoneId.of("Asia/Shanghai")
    private val offset = ZoneOffset.ofHours(8)
    private val today = LocalDate.now(zone)
    private val seedTag = "acceptance-20261025"

    @Test fun seedHealthFoodAndWeightData() {
        val store = Store(context)
        try {
            seedLocalHistory(store)
            HealthStore(store).categories(setOf(
                "SleepSession", "HeartRate", "HeartRateVariabilityRmssd", "Steps",
                "ExerciseSession", "Weight", "Nutrition", "ActiveCaloriesBurned", "Distance"
            ))
        } finally {
            store.close()
        }
    }

    private fun seedLocalHistory(store: Store) {
        val scaleId = "$seedTag-scale"
        store.writableDatabase.delete("food_meals", "id LIKE ?", arrayOf("$seedTag-%"))
        store.writableDatabase.delete("measurements", "id LIKE ?", arrayOf("$seedTag-%"))
        store.writableDatabase.delete("scales", "id LIKE ?", arrayOf("$seedTag-%"))
        store.bind(BoundScale(scaleId, "云麦体脂秤 3 SE", "02:00:00:00:00:01", true))

        val foods = FoodStore(store)
        val days: List<Pair<Int, List<SeededMeal>>> = listOf(
            0 to listOf(meal("牛油果鸡蛋吐司", 420.0, 20.0, 36.0, 23.0, "早餐"), meal("香煎三文鱼藜麦碗", 610.0, 36.0, 57.0, 25.0, "午餐")),
            21 to listOf(meal("燕麦酸奶杯", 310.0, 14.0, 42.0, 9.0, "早餐"), meal("番茄牛腩饭", 620.0, 32.0, 78.0, 18.0, "午餐"), meal("清蒸鲈鱼配米饭", 540.0, 35.0, 62.0, 13.0, "晚餐")),
            18 to listOf(meal("全麦鸡蛋三明治", 390.0, 21.0, 39.0, 14.0, "早餐"), meal("鸡胸肉沙拉", 460.0, 39.0, 32.0, 18.0, "午餐"), meal("牛肉面", 680.0, 30.0, 96.0, 18.0, "晚餐")),
            14 to listOf(meal("豆浆油条", 430.0, 12.0, 53.0, 19.0, "早餐"), meal("三文鱼藜麦碗", 610.0, 36.0, 57.0, 25.0, "午餐"), meal("家常豆腐配米饭", 520.0, 24.0, 70.0, 16.0, "晚餐")),
            10 to listOf(meal("希腊酸奶与蓝莓", 260.0, 17.0, 28.0, 8.0, "早餐"), meal("照烧鸡腿饭", 710.0, 34.0, 88.0, 24.0, "午餐"), meal("虾仁蔬菜意面", 590.0, 31.0, 76.0, 18.0, "晚餐")),
            7 to listOf(meal("鸡蛋牛油果吐司", 420.0, 20.0, 36.0, 23.0, "早餐"), meal("猪排咖喱饭", 760.0, 32.0, 92.0, 28.0, "午餐"), meal("菌菇鸡汤", 350.0, 26.0, 24.0, 16.0, "晚餐")),
            5 to listOf(meal("香蕉燕麦粥", 340.0, 12.0, 58.0, 8.0, "早餐"), meal("宫保鸡丁配米饭", 690.0, 35.0, 78.0, 24.0, "午餐"), meal("清炒时蔬", 180.0, 6.0, 20.0, 9.0, "晚餐")),
            3 to listOf(meal("美式咖啡与贝果", 360.0, 12.0, 55.0, 10.0, "早餐"), meal("牛肉汉堡与薯条", 920.0, 39.0, 94.0, 43.0, "午餐"), meal("番茄鸡蛋面", 560.0, 24.0, 77.0, 17.0, "晚餐")),
            1 to listOf(meal("酸奶水果麦片", 330.0, 15.0, 47.0, 9.0, "早餐"), meal("烤鸡腿配糙米", 650.0, 42.0, 67.0, 22.0, "午餐")),
        )
        for ((daysAgo, meals) in days) {
            meals.forEachIndexed { index, seeded ->
                val (name, nutrients, mealType) = seeded
                val hour = when (mealType) { "早餐" -> 8; "午餐" -> 12; else -> 19 }
                val time = pastTime(today.minusDays(daysAgo.toLong()), hour, 10) + index
                foods.save(Meal(
                    id = stableId("meal", daysAgo, index),
                    time = time,
                    offset = offset.totalSeconds,
                    mealType = mealTypeCode(mealType),
                    items = listOf(FoodItem(
                        id = stableId("food", daysAgo, index),
                        name = name,
                        portion = "1 份",
                        base = nutrients,
                        source = "manual",
                    )),
                    status = "synced",
                    platformId = stableId("nutrition-platform", daysAgo, index),
                    advice = MealAdvice(
                        healthiness = "以日常家常餐为主，蛋白质来源有变化。",
                        balance = "搭配蔬菜时更均衡。",
                        nextTime = "如果当天活动较多，可以留意主食份量。",
                        note = "这是手动记录的样例，不代表全天完整摄入。"
                    )
                ))
            }
        }

        for ((index, daysAgo) in listOf(0, 2, 4, 6, 9, 13, 17, 21).withIndex()) {
            val kg = listOf(71.8, 71.7, 71.65, 71.6, 71.55, 71.45, 71.4, 71.35)[index]
            val time = pastTime(today.minusDays(daysAgo.toLong()), 7, 22)
            store.save(Measurement(
                id = stableId("weight-local", daysAgo, 0),
                deviceId = scaleId,
                source = "云麦体脂秤 3 SE",
                kg = kg,
                time = time,
                offset = offset.totalSeconds,
                status = "synced",
                platformId = stableId("weight-platform", daysAgo, 0),
            ))
        }

        store.writableDatabase.delete("food_library", "id LIKE ?", arrayOf("$seedTag-%"))
        val healthy = listOf("燕麦酸奶杯", "全麦鸡蛋三明治", "三文鱼藜麦碗", "虾仁蔬菜意面")
        healthy.forEachIndexed { index, name ->
            val item = FoodItem(
                id = stableId("favorite", index, 0),
                name = name,
                portion = "1 份",
                base = Nutrients(if (index == 0) 310.0 else 520.0),
                source = "manual",
            )
            foods.favorite(item, true)
        }
    }

    private fun meal(name: String, kcal: Double, protein: Double, carbs: Double, fat: Double, type: String) =
        SeededMeal(name, Nutrients(kcal, protein, carbs, fat), type)

    private fun mealTypeCode(name: String) = when (name) { "早餐" -> 1; "午餐" -> 2; "晚餐" -> 3; else -> 0 }
    private fun pastTime(date: LocalDate, hour: Int, minute: Int): Long {
        val planned = date.atTime(hour, minute).atZone(zone).toInstant()
        return minOf(planned, Instant.now().minusSeconds(120)).toEpochMilli()
    }
    private fun stableId(prefix: String, day: Int, index: Int) = "$seedTag-$prefix-$day-$index"
}

private data class SeededMeal(val name: String, val nutrients: Nutrients, val type: String)
