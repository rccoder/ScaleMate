package app.scalemate.health

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class HealthCoachTopic { SLEEP, ACTIVITY, TRAINING, RECOVERY, WEIGHT }

sealed interface HealthCoachSignal {
    val topic:HealthCoachTopic
    val evidence:List<HealthEvidence>

    data class Sleep(
        val primary:HealthEvidence,
        val supplemental:List<HealthEvidence>,
        val baselineMedianMinutes:Double?,
        val goalMinutes:Int?
    ):HealthCoachSignal {
        override val topic=HealthCoachTopic.SLEEP
        override val evidence=listOf(primary)+supplemental
    }

    data class Activity(
        val latest:HealthEvidence,
        val baselineMedianSteps:Double?,
        val goalSteps:Long?
    ):HealthCoachSignal {
        override val topic=HealthCoachTopic.ACTIVITY
        override val evidence=listOf(latest)
    }

    data class Training(
        val rows:List<HealthEvidence>,
        val activeDays:Int,
        val totalMinutes:Double,
        val goalDays:Int?
    ):HealthCoachSignal {
        override val topic=HealthCoachTopic.TRAINING
        override val evidence=rows
    }

    data class Recovery(
        val latest:HealthEvidence,
        val baselineMedian:Double,
        val sleepHeartRate:HealthEvidence?
    ):HealthCoachSignal {
        override val topic=HealthCoachTopic.RECOVERY
        override val evidence=listOfNotNull(latest,sleepHeartRate)
    }

    data class Weight(
        val latest:HealthEvidence,
        val earliestRecent:HealthEvidence
    ):HealthCoachSignal {
        override val topic=HealthCoachTopic.WEIGHT
        override val evidence=listOf(latest,earliestRecent).distinctBy {it.id}
    }
}

data class HealthCoachBrief(
    val today:String,
    val dataAsOf:Long?,
    val oldCategories:Set<String>,
    val missingCategories:Set<String>,
    val signals:List<HealthCoachSignal>
)

object HealthCoach {
    private fun LocalDate.isOnOrBefore(other:LocalDate)=this<=other

    fun brief(
        snapshot:HealthSnapshot?,
        goals:HealthGoals,
        today:LocalDate=LocalDate.now(ZoneId.of(snapshot?.window?.zone ?: ZoneId.systemDefault().id)),
        now:Long=System.currentTimeMillis()
    ):HealthCoachBrief? {
        if(snapshot==null)return null
        fun date(e:HealthEvidence)=runCatching {LocalDate.parse(e.date)}.getOrNull()
        fun usable(e:HealthEvidence)=!e.partial&&e.period(snapshot.window)!="baseline"
        fun rows(kind:String,fields:Set<String>,complete:Boolean=true)=snapshot.evidence.filter {
            it.kind==kind&&it.field in fields&&date(it)?.isOnOrBefore(today)==true&&(!complete||usable(it))
        }.sortedBy {it.date}

        // Prefer measured asleep time, but keep a sleep session usable when stage coverage
        // is too low for an asleep estimate.
        val sleepRows=(rows("SleepSession",setOf("sessionMinutes"))+rows("SleepSession",setOf("sleepMinutes")))
            .groupBy {it.date}
            .map {(_,rows)->rows.maxByOrNull {it.field=="sleepMinutes"}!!}
            .sortedBy {it.date}
        val latestSleep=sleepRows.lastOrNull()
        val sleep=latestSleep?.let {primary->
            val baseline=sleepRows.filter {it.date<primary.date}.takeLast(7).map {it.value}
            val supplemental=snapshot.evidence.filter {
                it.kind=="SleepSession"&&it.date==primary.date&&it.field in setOf("additionalSleepMinutes","additionalSessionMinutes")
            }.sortedBy {it.field=="additionalSessionMinutes"}
            HealthCoachSignal.Sleep(
                primary,
                supplemental.take(1),
                HealthMetrics.median(baseline),
                goals.sleepMinutes
            )
        }

        val activityRows=rows("Steps",setOf("count"),complete=false)
        val latestActivity=activityRows.lastOrNull()
        val activity=latestActivity?.let {latest->
            val baseline=activityRows.filter {it.date<latest.date&&!it.partial}.takeLast(7).map {it.value}
            HealthCoachSignal.Activity(latest,HealthMetrics.median(baseline),goals.steps)
        }

        val trainingStart=today.minusDays(6)
        val trainingRows=rows("ExerciseSession",setOf("exerciseDuration"))
            .filter {date(it)?.let {d->d>=trainingStart&&d<=today}==true}
            .groupBy {it.date}
            .map {(_,rows)->rows.maxByOrNull {it.value}!!}
            .sortedBy {it.date}
        val training=trainingRows.takeIf {it.isNotEmpty()}?.let {list->
            HealthCoachSignal.Training(
                list,
                list.map {it.date}.distinct().size,
                list.sumOf {it.value},
                goals.workoutDays
            )
        }

        val hrvRows=rows("HeartRateVariabilityRmssd",setOf("nightRmssd","sampleMedian"))
        val latestHrv=hrvRows.lastOrNull()
        val recovery=latestHrv?.let {latest->
            val baseline=hrvRows.filter {it.date<latest.date}.takeLast(7).map {it.value}
            val baselineMedian=HealthMetrics.median(baseline) ?: return@let null
            val sleepHeartRate=snapshot.evidence.filter {
                it.kind=="HeartRate"&&it.field=="sleepBpm"&&it.date==latest.date
            }.maxByOrNull {it.id}
            HealthCoachSignal.Recovery(latest,baselineMedian,sleepHeartRate)
        }

        val weightRows=rows("Weight",setOf("weight"))
        val weight=weightRows.lastOrNull()?.let {latest->
            val earliest=weightRows.filter {it.date<latest.date}.takeLast(7).firstOrNull() ?: return@let null
            HealthCoachSignal.Weight(latest,earliest)
        }

        val ordered=listOfNotNull(sleep,activity,training,recovery,weight)
        val missing=snapshot.coverage.filter {it.status in setOf("no_records","not_authorized","unsupported","read_failed")}.map {it.kind}.toSet()
        val activeKinds=setOf("Steps","HeartRate")
        val old=snapshot.coverage.filter {coverage->
            coverage.kind in activeKinds&&coverage.latest?.let {now-it>6*60*60*1000}==true
        }.map {it.kind}.toSet()
        return HealthCoachBrief(today.toString(),snapshot.dataAsOf,old,missing,ordered)
    }

    fun dateSpan(signal:HealthCoachSignal):Long? {
        val dates=signal.evidence.mapNotNull {runCatching {LocalDate.parse(it.date)}.getOrNull()}
        return dates.minOrNull()?.let {ChronoUnit.DAYS.between(it,dates.maxOrNull())} ?: 0
    }

    fun ageText(dataAsOf:Long?,now:Long=System.currentTimeMillis()):Long? =
        dataAsOf?.let {(now-it).coerceAtLeast(0)}

    fun coachQuestion():String =
        "Create today's wellness coaching note from the supplied facts. Start with the one thing that matters most today, then give one practical next action. Mention only limitations that change the advice. Keep it concise, do not repeat every metric, and do not diagnose or prescribe."
}
