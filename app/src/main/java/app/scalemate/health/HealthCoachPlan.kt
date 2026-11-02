package app.scalemate.health

import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

enum class HealthCoachFocus { BALANCE, SLEEP, ACTIVITY, WEIGHT }
enum class HealthCoachEnergy { NORMAL, TIRED }
enum class HealthCoachFeedback { PROPOSED, ACCEPTED, DONE, PARTIAL, MISSED, TOO_HARD }
enum class HealthCoachGoalType { HABIT, SLEEP, ACTIVITY, WEIGHT, BODY_FAT, STRENGTH }

data class HealthCoachProfile(
    val focus:HealthCoachFocus=HealthCoachFocus.BALANCE,
    val minutes:Int=15,
    val energy:HealthCoachEnergy=HealthCoachEnergy.NORMAL,
    val energyDate:String="",
    val goalType:HealthCoachGoalType=HealthCoachGoalType.HABIT,
    val target:String="",
    val days:Set<Int> = emptySet(),
    val equipment:String="",
    val limitations:String=""
) {
    init {
        require(minutes in setOf(5,15,30))
        require(days.all {it in 1..7})
        require(target.length<=120&&equipment.length<=120&&limitations.length<=240)
    }
    fun forDay(today:LocalDate)=if(energyDate==today.toString())this else copy(energy=HealthCoachEnergy.NORMAL,energyDate="")
}

data class HealthCoachMemory(
    val id:String=healthId(),
    val text:String,
    val kind:String="preference",
    val createdAt:Long=System.currentTimeMillis(),
    val expiresAt:String=""
) {
    init {require(text.isNotBlank()&&text.length<=240)}
    fun active(today:LocalDate)=expiresAt.isBlank()||runCatching {LocalDate.parse(expiresAt)>=today}.getOrDefault(false)
}

data class HealthCoachMemoryCandidate(
    val text:String,
    val kind:String="preference",
    val expiresAt:String="",
    val status:String="proposed"
) {
    init {
        require(text.isNotBlank()&&text.length<=240)
        require(kind in setOf("preference","schedule","limitation","background","temporary"))
        require(status in setOf("proposed","accepted","dismissed"))
        require(expiresAt.isBlank()||runCatching {LocalDate.parse(expiresAt)}.isSuccess)
    }
}

data class HealthCoachPlanChangeCandidate(
    val date:String,
    val action:String,
    val minutes:Int?=null,
    val days:Set<Int> = emptySet(),
    val temporary:Boolean=true,
    val reason:String="",
    val status:String="proposed"
) {
    init {
        require(runCatching {LocalDate.parse(date)}.isSuccess)
        require(action.isNotBlank()&&action.length<=500)
        require(minutes==null||minutes in 5..240)
        require(days.all {it in 1..7})
        require(temporary||days.isNotEmpty())
        require(reason.length<=300)
        require(status in setOf("proposed","accepted","dismissed"))
        require(!action.contains(Regex("https?://")))
    }
}

data class HealthCoachScheduleChange(
    val id:String=healthId(),
    val date:String,
    val action:String,
    val minutes:Int?=null,
    val reason:String="",
    val createdAt:Long=System.currentTimeMillis()
) {
    init {
        require(runCatching {LocalDate.parse(date)}.isSuccess)
        require(action.isNotBlank()&&action.length<=500)
        require(minutes==null||minutes in 5..240)
        require(reason.length<=300)
    }
    fun active(today:LocalDate)=runCatching {LocalDate.parse(date)>=today}.getOrDefault(false)
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
    private val profileMinutes=setOf(5,15,30)

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

    fun profile(p:HealthCoachProfile)=JSONObject().put("focus",p.focus.name).put("minutes",p.minutes)
        .put("energy",p.energy.name).put("energyDate",p.energyDate)
        .put("goalType",p.goalType.name).put("target",p.target).put("days",JSONArray(p.days.sorted()))
        .put("equipment",p.equipment).put("limitations",p.limitations)
    fun profile(o:JSONObject)=HealthCoachProfile(
        HealthCoachFocus.valueOf(o.optString("focus",HealthCoachFocus.BALANCE.name)),
        o.optInt("minutes",15),
        HealthCoachEnergy.valueOf(o.optString("energy",HealthCoachEnergy.NORMAL.name)),
        o.optString("energyDate"),
        HealthCoachGoalType.valueOf(o.optString("goalType",HealthCoachGoalType.HABIT.name)),
        o.optString("target"),
        o.optJSONArray("days")?.let {rows->(0 until rows.length()).map {rows.getInt(it)}.toSet()} ?: emptySet(),
        o.optString("equipment"),o.optString("limitations")
    )
    fun memory(m:HealthCoachMemory)=JSONObject().put("id",m.id).put("text",m.text).put("kind",m.kind)
        .put("createdAt",m.createdAt).put("expiresAt",m.expiresAt)
    fun memory(o:JSONObject)=HealthCoachMemory(
        o.getString("id"),o.getString("text"),o.optString("kind","preference"),
        o.optLong("createdAt"),o.optString("expiresAt")
    )
    fun memoryCandidate(c:HealthCoachMemoryCandidate)=JSONObject().put("text",c.text).put("kind",c.kind)
        .put("expiresAt",c.expiresAt).put("status",c.status)
    fun memoryCandidate(o:JSONObject)=HealthCoachMemoryCandidate(
        o.getString("text"),o.optString("kind","preference"),o.optString("expiresAt"),
        o.optString("status","proposed")
    )
    fun planChangeCandidate(c:HealthCoachPlanChangeCandidate)=JSONObject().put("date",c.date)
        .put("action",c.action).put("minutes",c.minutes ?: JSONObject.NULL).put("days",JSONArray(c.days.sorted()))
        .put("temporary",c.temporary).put("reason",c.reason).put("status",c.status)
    fun planChangeCandidate(o:JSONObject)=HealthCoachPlanChangeCandidate(
        o.getString("date"),o.getString("action"),
        if(o.isNull("minutes"))null else o.getInt("minutes"),
        o.optJSONArray("days")?.let {rows->(0 until rows.length()).map {rows.getInt(it)}.toSet()} ?: emptySet(),
        o.optBoolean("temporary",true),o.optString("reason"),o.optString("status","proposed")
    )
    fun scheduleChange(c:HealthCoachScheduleChange)=JSONObject().put("id",c.id).put("date",c.date)
        .put("action",c.action).put("minutes",c.minutes ?: JSONObject.NULL).put("reason",c.reason)
        .put("createdAt",c.createdAt)
    fun scheduleChange(o:JSONObject)=HealthCoachScheduleChange(
        o.getString("id"),o.getString("date"),o.getString("action"),
        if(o.isNull("minutes"))null else o.getInt("minutes"),o.optString("reason"),o.optLong("createdAt")
    )
    fun acceptedMemory(candidate:HealthCoachMemoryCandidate,now:Long=System.currentTimeMillis())=
        HealthCoachMemory(text=candidate.text.trim(),kind=candidate.kind,createdAt=now,expiresAt=candidate.expiresAt)
    fun acceptedScheduleChange(candidate:HealthCoachPlanChangeCandidate,now:Long=System.currentTimeMillis())=
        HealthCoachScheduleChange(date=candidate.date,action=candidate.action.trim(),minutes=candidate.minutes,
            reason=candidate.reason.trim(),createdAt=now)
    fun applyPermanentSchedule(profile:HealthCoachProfile,candidate:HealthCoachPlanChangeCandidate):HealthCoachProfile {
        require(!candidate.temporary)
        require(candidate.days.isNotEmpty())
        return profile.copy(
            days=candidate.days,
            minutes=candidate.minutes?.takeIf {it in profileMinutes} ?: profile.minutes
        )
    }
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
