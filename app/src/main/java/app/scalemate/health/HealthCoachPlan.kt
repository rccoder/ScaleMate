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
    val limitations:String="",
    val baseline:String="",
    val deadline:String="",
    val successCriteria:String=""
) {
    init {
        require(minutes in setOf(5,15,30))
        require(days.all {it in 1..7})
        require(target.length<=120&&equipment.length<=120&&limitations.length<=240)
        require(baseline.length<=160&&successCriteria.length<=240)
        require(deadline.length<=10)
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
    val evidence:List<HealthEvidence> = emptyList(),
    val kind:String=profile.goalType.name,
    val successCriteria:String="",
    val fallback:String="",
    val source:String="ai"
)

data class HealthCoachActionCopy(
    val kind:String,
    val observation:String,
    val action:String,
    val successCriteria:String,
    val fallback:String
)

enum class HealthCoachReviewAction { SET_UP, KEEP, EASE, ADVANCE }

data class HealthCoachWeekReview(
    val weekStart:String,
    val weekEnd:String,
    val scheduledDays:Int,
    val completedDays:Int,
    val partialDays:Int,
    val missedDays:Int,
    val tooHardDays:Int,
    val action:HealthCoachReviewAction,
    val currentDays:Set<Int>,
    val nextDays:Set<Int>,
    val currentMinutes:Int,
    val nextMinutes:Int
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

    fun history(plans:List<HealthCoachPlan>,today:LocalDate):List<HealthCoachPlan> = plans
        .filter {runCatching {LocalDate.parse(it.date) in today.minusDays(90)..today}.getOrDefault(false)}
        .distinctBy {it.message.id}.sortedByDescending {it.message.createdAt}.take(180)

    fun completedDays(plans:List<HealthCoachPlan>,today:LocalDate):Int =
        recent(plans,today).filter {it.feedback==HealthCoachFeedback.DONE}.map {it.date}.distinct().size

    fun currentWeek(today:LocalDate):Pair<LocalDate,LocalDate> {
        val start=today.with(java.time.DayOfWeek.MONDAY)
        return start to start.plusDays(6)
    }

    fun reviewWeek(today:LocalDate):Pair<LocalDate,LocalDate> {
        val thisWeek=today.with(java.time.DayOfWeek.MONDAY)
        return if(today.dayOfWeek==java.time.DayOfWeek.MONDAY) thisWeek.minusWeeks(1) to thisWeek.minusDays(1)
        else thisWeek to thisWeek.plusDays(6)
    }

    fun localAction(profile:HealthCoachProfile,chinese:Boolean):HealthCoachActionCopy {
        val minutes=profile.minutes
        return when(profile.goalType) {
            HealthCoachGoalType.SLEEP->HealthCoachActionCopy(
                "SLEEP",
                if(chinese)"按已确认的周计划，今天只守住一个作息动作。" else "For the confirmed weekly plan, keep one sleep routine action today.",
                if(chinese)"今晚在固定时间结束屏幕活动，按计划上床。" else "Stop screens at your usual time tonight and go to bed as planned.",
                if(chinese)"按计划上床，不追求额外运动。" else "Get to bed at the planned time; no extra workout is required.",
                if(chinese)"今晚加班时，只保留关屏和放松步骤。" else "If work runs late, keep only the screen-off and wind-down step."
            )
            HealthCoachGoalType.WEIGHT->HealthCoachActionCopy(
                "WEIGH",
                if(chinese)"按已确认的周计划，今天先获得一个可比较的体重记录。" else "For the confirmed weekly plan, get one comparable weight record today.",
                if(chinese)"在起床后、早餐前完成一次称重并记录。" else "Weigh yourself after waking and before breakfast, then record it.",
                if(chinese)"在相近条件下完成一次称重。" else "Complete one weigh-in under similar conditions.",
                if(chinese)"今天无法称重就跳过，不用其他数值代替。" else "If weighing is not possible today, skip it rather than substituting another value."
            )
            HealthCoachGoalType.BODY_FAT->HealthCoachActionCopy(
                "BODY_FAT",
                if(chinese)"按已确认的周计划，今天先保持测量条件一致。" else "For the confirmed weekly plan, keep measurement conditions consistent today.",
                if(chinese)"在与上次相近的时间和条件下记录一次体脂读数。" else "Record one body-fat reading at a similar time and under similar conditions as last time.",
                if(chinese)"使用同一设备和相近条件完成一次记录。" else "Use the same device and similar conditions for one reading.",
                if(chinese)"读不到数据就跳过，不从体重推算体脂。" else "If the reading is unavailable, skip it rather than estimating body fat from weight."
            )
            HealthCoachGoalType.STRENGTH->HealthCoachActionCopy(
                "STRENGTH",
                if(chinese)"按已确认的周计划，今天先完成一次基础力量练习。" else "For the confirmed weekly plan, complete one basic strength session today.",
                if(chinese)"做 $minutes 分钟基础力量练习，保留一次轻松余力。" else "Do $minutes minutes of basic strength work, keeping one easy repetition in reserve.",
                if(chinese)"完成 $minutes 分钟，并在最后保留轻松余力。" else "Complete $minutes minutes and finish with an easy reserve.",
                if(chinese)"今天太累时，只做一两个主要动作或改成散步。" else "If you are very tired, do only one or two main movements or switch to a walk."
            )
            HealthCoachGoalType.ACTIVITY->HealthCoachActionCopy(
                "ACTIVITY",
                if(chinese)"按已确认的周计划，今天先完成一段容易安排的活动。" else "For the confirmed weekly plan, complete one easy-to-schedule activity today.",
                if(chinese)"安排 $minutes 分钟舒适活动，例如饭后散步。" else "Schedule $minutes minutes of comfortable activity, such as a walk after a meal.",
                if(chinese)"完成 $minutes 分钟舒适活动，不必追赶步数。" else "Complete $minutes minutes of comfortable activity without chasing a step total.",
                if(chinese)"时间不足时，改为 5 分钟散步。" else "If time is short, switch to a five-minute walk."
            )
            HealthCoachGoalType.HABIT->HealthCoachActionCopy(
                "HABIT",
                if(chinese)"按已确认的周计划，今天只做一个容易重复的小动作。" else "For the confirmed weekly plan, do one small action that is easy to repeat today.",
                if(chinese)"安排 $minutes 分钟轻量活动，放在平时最容易执行的时间。" else "Schedule $minutes minutes of light activity at the time that is easiest to keep.",
                if(chinese)"完成 $minutes 分钟即可，不增加额外任务。" else "Complete $minutes minutes only; no extra task is required.",
                if(chinese)"今天没时间时，改为 5 分钟并保留这个习惯。" else "If there is no time today, reduce it to five minutes and keep the habit."
            )
        }
    }

    fun weekProgress(profile:HealthCoachProfile,plans:List<HealthCoachPlan>,today:LocalDate):HealthCoachWeekReview {
        val (start,end)=currentWeek(today)
        return weekReview(start,end,profile,plans,today)
    }

    fun weekReview(profile:HealthCoachProfile,plans:List<HealthCoachPlan>,today:LocalDate):HealthCoachWeekReview {
        val (start,end)=reviewWeek(today)
        return weekReview(start,end,profile,plans,today)
    }

    private fun weekReview(
        start:LocalDate,end:LocalDate,profile:HealthCoachProfile,plans:List<HealthCoachPlan>,today:LocalDate
    ):HealthCoachWeekReview {
        val byDate=plans.filter {runCatching {LocalDate.parse(it.date) in start..end}.getOrDefault(false)}.associateBy {it.date}
        val scheduled=if(profile.days.isEmpty())byDate.keys.mapNotNull {runCatching {LocalDate.parse(it)}.getOrNull()}.sorted()
        else buildList {
            var date=start
            while(date<=end) {
                if(date.dayOfWeek.value in profile.days)add(date)
                date=date.plusDays(1)
            }
        }
        val completed=scheduled.count {byDate[it.toString()]?.feedback==HealthCoachFeedback.DONE}
        val partial=scheduled.count {byDate[it.toString()]?.feedback==HealthCoachFeedback.PARTIAL}
        val tooHard=scheduled.count {byDate[it.toString()]?.feedback==HealthCoachFeedback.TOO_HARD}
        val missed=scheduled.count {date->
            val feedback=byDate[date.toString()]?.feedback
            date<today&&(feedback==null||feedback in setOf(HealthCoachFeedback.PROPOSED,HealthCoachFeedback.ACCEPTED,HealthCoachFeedback.MISSED))
        }
        val action=when {
            scheduled.isEmpty()->HealthCoachReviewAction.SET_UP
            tooHard>0||completed*2<scheduled.size->HealthCoachReviewAction.EASE
            completed==scheduled.size&&scheduled.size<=2&&profile.focus in setOf(HealthCoachFocus.ACTIVITY)->HealthCoachReviewAction.ADVANCE
            else->HealthCoachReviewAction.KEEP
        }
        val nextDays=when(action) {
            HealthCoachReviewAction.EASE->if(profile.days.size>1)profile.days.sorted().dropLast(1).toSet() else profile.days
            HealthCoachReviewAction.ADVANCE->profile.days+(1..7).firstOrNull {it !in profile.days}.let {if(it==null)emptySet() else setOf(it)}
            else->profile.days
        }
        val nextMinutes=if(action==HealthCoachReviewAction.EASE&&profile.days.size<=1)when(profile.minutes) {
            30->15
            15->5
            else->5
        } else profile.minutes
        return HealthCoachWeekReview(
            start.toString(),end.toString(),scheduled.size,completed,partial,missed,tooHard,action,
            profile.days,nextDays,profile.minutes,nextMinutes
        )
    }

    fun profile(p:HealthCoachProfile)=JSONObject().put("focus",p.focus.name).put("minutes",p.minutes)
        .put("energy",p.energy.name).put("energyDate",p.energyDate)
        .put("goalType",p.goalType.name).put("target",p.target).put("days",JSONArray(p.days.sorted()))
        .put("equipment",p.equipment).put("limitations",p.limitations)
        .put("baseline",p.baseline).put("deadline",p.deadline).put("successCriteria",p.successCriteria)
    fun profile(o:JSONObject)=HealthCoachProfile(
        HealthCoachFocus.valueOf(o.optString("focus",HealthCoachFocus.BALANCE.name)),
        o.optInt("minutes",15),
        HealthCoachEnergy.valueOf(o.optString("energy",HealthCoachEnergy.NORMAL.name)),
        o.optString("energyDate"),
        HealthCoachGoalType.valueOf(o.optString("goalType",HealthCoachGoalType.HABIT.name)),
        o.optString("target"),
        o.optJSONArray("days")?.let {rows->(0 until rows.length()).map {rows.getInt(it)}.toSet()} ?: emptySet(),
        o.optString("equipment"),o.optString("limitations"),
        o.optString("baseline"),o.optString("deadline"),o.optString("successCriteria")
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
        .put("kind",p.kind).put("successCriteria",p.successCriteria).put("fallback",p.fallback).put("source",p.source)
    fun plan(o:JSONObject)=HealthCoachPlan(
        HealthJson.message(o.getJSONObject("message")),o.getString("date"),o.getString("action"),
        profile(o.getJSONObject("profile")),HealthJson.strings(o.getJSONArray("categories")).toSet(),
        HealthCoachFeedback.valueOf(o.getString("feedback")),
        o.optJSONArray("evidence")?.let {rows->(0 until rows.length()).map {HealthJson.evidence(rows.getJSONObject(it))}}.orEmpty(),
        o.optString("kind",o.getJSONObject("profile").optString("goalType",HealthCoachGoalType.HABIT.name)),
        o.optString("successCriteria"),o.optString("fallback"),o.optString("source","ai")
    )
}
