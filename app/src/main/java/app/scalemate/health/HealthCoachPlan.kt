package app.scalemate.health

import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

enum class HealthCoachFocus { BALANCE, SLEEP, ACTIVITY, WEIGHT }
enum class HealthCoachEnergy { NORMAL, TIRED }
enum class HealthCoachFeedback { PROPOSED, ACCEPTED, DONE, MISSED, TOO_HARD }

data class HealthCoachProfile(
    val focus:HealthCoachFocus=HealthCoachFocus.BALANCE,
    val minutes:Int=15,
    val energy:HealthCoachEnergy=HealthCoachEnergy.NORMAL,
    val energyDate:String=""
) {
    init {require(minutes in setOf(5,15,30))}
    fun forDay(today:LocalDate)=if(energyDate==today.toString())this else copy(energy=HealthCoachEnergy.NORMAL,energyDate="")
}

data class HealthCoachPlan(
    val message:HealthMessage,
    val date:String,
    val action:String,
    val profile:HealthCoachProfile,
    val categories:Set<String>,
    val feedback:HealthCoachFeedback=HealthCoachFeedback.PROPOSED,
    val evidence:List<HealthEvidence> = emptyList()
)

object HealthCoachPlans {
    fun generated(
        message:HealthMessage,today:LocalDate,action:String,profile:HealthCoachProfile,
        categories:Set<String>,history:List<HealthCoachPlan>,evidence:List<HealthEvidence> = emptyList()
    ):HealthCoachPlan {
        val done=recent(history,today).firstOrNull {
            it.date==today.toString()&&it.feedback==HealthCoachFeedback.DONE&&it.profile.focus==profile.focus&&categories.containsAll(it.categories)
        }
        // Refreshing an insight must not turn a completed daily action into another obligation.
        return done?.copy(message=message.copy(id=done.message.id),profile=profile,categories=categories,evidence=evidence)
            ?: HealthCoachPlan(message,today.toString(),action,profile,categories,evidence=evidence)
    }
    fun recent(plans:List<HealthCoachPlan>,today:LocalDate):List<HealthCoachPlan> = plans
        .filter {runCatching {LocalDate.parse(it.date) in today.minusDays(6)..today}.getOrDefault(false)}
        .distinctBy {it.message.id}.sortedByDescending {it.message.createdAt}.take(14)

    fun completedDays(plans:List<HealthCoachPlan>,today:LocalDate):Int =
        recent(plans,today).filter {it.feedback==HealthCoachFeedback.DONE}.map {it.date}.distinct().size

    fun profile(p:HealthCoachProfile)=JSONObject().put("focus",p.focus.name).put("minutes",p.minutes).put("energy",p.energy.name).put("energyDate",p.energyDate)
    fun profile(o:JSONObject)=HealthCoachProfile(
        HealthCoachFocus.valueOf(o.getString("focus")),o.getInt("minutes"),HealthCoachEnergy.valueOf(o.getString("energy")),o.optString("energyDate")
    )
    fun json(p:HealthCoachPlan)=JSONObject().put("message",HealthJson.message(p.message)).put("date",p.date)
        .put("action",p.action).put("profile",profile(p.profile)).put("categories",JSONArray(p.categories.sorted()))
        .put("feedback",p.feedback.name).put("evidence",JSONArray(p.evidence.map(HealthJson::evidence)))
    fun plan(o:JSONObject)=HealthCoachPlan(
        HealthJson.message(o.getJSONObject("message")),o.getString("date"),o.getString("action"),
        profile(o.getJSONObject("profile")),HealthJson.strings(o.getJSONArray("categories")).toSet(),
        HealthCoachFeedback.valueOf(o.getString("feedback")),
        o.optJSONArray("evidence")?.let {rows->(0 until rows.length()).map {HealthJson.evidence(rows.getJSONObject(it))}}.orEmpty()
    )
}
