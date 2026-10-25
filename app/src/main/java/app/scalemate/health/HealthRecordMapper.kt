package app.scalemate.health

import android.annotation.SuppressLint
import android.health.connect.datatypes.InstantRecord
import android.health.connect.datatypes.IntervalRecord
import android.health.connect.datatypes.Record
import android.health.connect.datatypes.SleepSessionRecord
import java.time.Instant
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject

/** Only platform numeric/enum/unit getters are read. Metadata, names and notes stay private. */
object HealthRecordMapper {
    private val units=mapOf("Mass" to ("getInGrams" to "g"),"Energy" to ("getInCalories" to "cal"),"Length" to ("getInMeters" to "m"),"Power" to ("getInWatts" to "W"),"Velocity" to ("getInMetersPerSecond" to "m/s"),"Volume" to ("getInLiters" to "L"),"Pressure" to ("getInMillimetersOfMercury" to "mmHg"),"Temperature" to ("getInCelsius" to "C"),"TemperatureDelta" to ("getInCelsius" to "C"),"BloodGlucose" to ("getInMillimolesPerLiter" to "mmol/L"),"Percentage" to ("getValue" to "%"))
    private val excluded=setOf("getClass","getMetadata","getId","getClientRecordId","getPlannedExerciseSessionId","getNotes","getTitle","getMealName","getRoute")
    fun value(obj:Any?):HealthValue? {
        if(obj is Number)return obj.toDouble().takeIf {it.isFinite()&&(it==0.0||kotlin.math.abs(it)>=1e-300)}?.let {HealthValue(it,"value")}
        if(obj==null)return null
        val (getter,unit)=units[obj.javaClass.simpleName] ?: return null
        val n=(obj.javaClass.getMethod(getter).invoke(obj) as Number).toDouble()
        // Health Connect uses Double.MIN_VALUE and similar denormals for unset optional fields.
        if(!n.isFinite()||n!=0.0&&kotlin.math.abs(n)<1e-300)return null
        return if(unit=="cal")HealthValue(n/1000.0,"kcal") else HealthValue(n,unit)
    }
    fun numeric(obj:Any):Map<String,HealthValue> = buildMap {
        obj.javaClass.methods.filter {it.parameterCount==0&&it.name.startsWith("get")&&it.name !in excluded&&it.declaringClass.name.startsWith("android.health.connect.datatypes")}.sortedBy {it.name}.forEach {method->
            val field=method.name.removePrefix("get").replaceFirstChar {it.lowercase()}
            value(method.invoke(obj))?.let {put(field,if(field=="heartRateVariabilityMillis")it.copy(unit="ms") else if(field in setOf("beatsPerMinute","rate")&&obj.javaClass.simpleName.contains("HeartRate"))it.copy(unit="bpm") else if(method.returnType==Int::class.javaPrimitiveType)it.copy(unit="enum") else it)}
        }
    }
    private fun detail(obj:Any,depth:Int=0):Any? {
        require(depth<=8){"Health detail nesting exceeds the supported schema"}
        value(obj)?.let {return JSONObject().put("value",it.value).put("unit",it.unit)}
        if(obj.javaClass.simpleName in units)return null
        if(obj is Instant||obj is ZoneOffset)return obj.toString()
        if(obj is java.time.Duration)return JSONObject().put("seconds",obj.seconds)
        if(obj is Collection<*>){require(obj.size<=10000);return JSONArray(obj.filterNotNull().mapNotNull {detail(it,depth+1)})}
        if(!obj.javaClass.name.startsWith("android.health.connect.datatypes."))return null
        val result=JSONObject()
        obj.javaClass.methods.filter {it.parameterCount==0&&it.name.startsWith("get")&&it.name !in excluded&&it.name !in setOf("getSamples","getStages","getDeltas")&&!it.name.endsWith("Id")&&it.declaringClass.name.startsWith("android.health.connect.datatypes.")}.sortedBy {it.name}.forEach {m->
            val child=m.invoke(obj) ?: return@forEach
            detail(child,depth+1)?.let {result.put(m.name.removePrefix("get").replaceFirstChar {c->c.lowercase()},it)}
        }
        return result.takeIf {it.length()>0}
    }
    // HealthReader checks class availability before requesting this optional API 36 extension type.
    @SuppressLint("NewApi")
    fun map(record:Record,kind:HealthKind,source:String):HealthRecord {
        val start=when(record){is InstantRecord->record.time;is IntervalRecord->record.startTime;else->error("Unsupported platform record")}
        val end=(record as? IntervalRecord)?.endTime ?: start
        val offset=when(record){is InstantRecord->record.zoneOffset;is IntervalRecord->record.startZoneOffset;else->null}
        if(kind.id=="ExerciseRoute") {
            val route=(record as android.health.connect.datatypes.ExerciseSessionRecord).route
            val locations=route?.routeLocations.orEmpty()
            var meters=0.0
            locations.zipWithNext().forEach {(a,b)->val lat1=Math.toRadians(a.latitude);val lat2=Math.toRadians(b.latitude);val dLat=lat2-lat1;val dLon=Math.toRadians(b.longitude-a.longitude);val h=kotlin.math.sin(dLat/2).let {it*it}+kotlin.math.cos(lat1)*kotlin.math.cos(lat2)*kotlin.math.sin(dLon/2).let {it*it};meters+=6371000*2*kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0,1.0)))}
            val points=JSONArray(locations.map {p->JSONObject().put("time",p.time).put("latitude",p.latitude).put("longitude",p.longitude).put("altitudeMeters",p.altitude?.inMeters ?: JSONObject.NULL)})
            return HealthRecord(healthHash(kind.id+record.metadata.id).take(24),kind.id,source,start.toEpochMilli(),end.toEpochMilli(),if(locations.isEmpty())mapOf("routeRequiresConsent" to HealthValue(1.0,"flag")) else mapOf("distance" to HealthValue(meters,"m"),"locationCount" to HealthValue(locations.size.toDouble(),"points")),detail=JSONObject().put("route",points).toString(),offsetSeconds=offset?.totalSeconds,platformId=record.metadata.id)
        }
        if(kind.id=="SkinTemperature"&&record is android.health.connect.datatypes.SkinTemperatureRecord) {
            val deltas=record.deltas.mapNotNull {d->
                val value=value(d.delta) ?: return@mapNotNull null
                HealthPoint(d.time.toEpochMilli(),value.value,"C")
            }.filter {it.value.isFinite()}
            val baseline=value(record.baseline)?.value
            val values=buildMap {
                if(baseline!=null)put("skinTemperatureBaseline",HealthValue(baseline,"C"))
                put("measurementLocation",HealthValue(record.measurementLocation.toDouble(),"enum"))
                if(deltas.isNotEmpty()) {
                    put("skinTemperatureDelta",HealthValue(deltas.map {it.value}.average(),"C"))
                    put("skinTemperatureDeltaMin",HealthValue(deltas.minOf {it.value},"C"))
                    put("skinTemperatureDeltaMax",HealthValue(deltas.maxOf {it.value},"C"))
                }
            }
            val nested=detail(record) as? JSONObject ?: JSONObject()
            return HealthRecord(healthHash(kind.id+record.metadata.dataOrigin.packageName+record.metadata.id).take(24),kind.id,source,start.toEpochMilli(),end.toEpochMilli(),values,deltas,emptyList(),nested.toString(),offset?.totalSeconds,record.metadata.id)
        }
        val samples=record.javaClass.methods.firstOrNull {it.name=="getSamples"&&it.parameterCount==0}?.invoke(record) as? List<*>
            ?: record.javaClass.methods.firstOrNull {it.name=="getDeltas"&&it.parameterCount==0}?.invoke(record) as? List<*>
        val points=samples.orEmpty().mapNotNull {sample->
            if(sample==null)return@mapNotNull null
            val time=sample.javaClass.getMethod("getTime").invoke(sample) as Instant
            val v=numeric(sample).values.firstOrNull() ?: return@mapNotNull null
            HealthPoint(time.toEpochMilli(),v.value,if(kind.id=="HeartRate")"bpm" else v.unit)
        }
        val stages=(record as? SleepSessionRecord)?.stages.orEmpty().map {HealthStage(it.startTime.toEpochMilli(),it.endTime.toEpochMilli(),it.type)}
        val nested=detail(record) as? JSONObject ?: JSONObject()
        return HealthRecord(healthHash(kind.id+record.metadata.dataOrigin.packageName+record.metadata.id).take(24),kind.id,source,start.toEpochMilli(),end.toEpochMilli(),numeric(record),points,stages,if(nested.length()>0)nested.toString() else "",offset?.totalSeconds,record.metadata.id)
    }
}
