package app.scalemate.health

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject

/** Export clinical values and codes, never patient/practitioner identities or FHIR references. */
object HealthFhir {
    private val allowed=setOf("resourceType","status","clinicalStatus","verificationStatus","code","coding","system","value","unit","valueQuantity","valueInteger","valueDecimal","valueBoolean","valueCodeableConcept","component","effectiveDateTime","effectivePeriod","start","end","issued","onsetDateTime","onsetPeriod","abatementDateTime","recordedDate","date","occurrenceDateTime","performedDateTime","performedPeriod","category","interpretation","referenceRange","low","high","dosageInstruction","doseAndRate","doseQuantity","route","method","medicationCodeableConcept","vaccineCode","quantity","type")
    private fun clean(value:Any?,key:String=""):Any?=when(value) {
        is JSONObject->JSONObject().apply {value.keys().asSequence().filter {it in allowed}.sorted().forEach {k->clean(value.opt(k),k)?.let {put(k,it)}}}
        is JSONArray->JSONArray((0 until value.length()).mapNotNull {clean(value.opt(it),key)})
        is Number->value.toDouble().takeIf {it.isFinite()}
        is Boolean->value
        is String->when {
            key=="system"->value.takeIf {it in setOf("http://loinc.org","http://snomed.info/sct","http://unitsofmeasure.org","http://hl7.org/fhir/sid/icd-10","http://hl7.org/fhir/sid/icd-10-cm")}
            key in setOf("effectiveDateTime","issued","onsetDateTime","abatementDateTime","recordedDate","date","occurrenceDateTime","performedDateTime","start","end")->value.takeIf {time(it)!=null}
            key in setOf("code","status","resourceType","unit")->value.takeIf {it.length<=100&&it.matches(Regex("[A-Za-z0-9%/_.+^{}() :\\-]+"))}
            else->null
        }
        else->null
    }
    fun sanitize(raw:String):JSONObject=clean(JSONObject(raw)) as JSONObject
    fun time(value:String):Long?=runCatching {Instant.parse(value).toEpochMilli()}.getOrNull() ?: runCatching {LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()}.getOrNull()
    fun date(o:JSONObject):Long? {
        for(k in listOf("effectiveDateTime","onsetDateTime","occurrenceDateTime","performedDateTime","recordedDate","issued","date"))time(o.optString(k))?.let {return it}
        for(k in listOf("effectivePeriod","onsetPeriod","performedPeriod"))o.optJSONObject(k)?.let {time(it.optString("start"))?.let {t->return t}}
        return null
    }
    fun numbers(o:JSONObject):Map<String,HealthValue> = buildMap {
        fun visit(obj:Any,path:String) {
            when(obj) {
                is JSONObject->{
                    obj.optJSONObject("valueQuantity")?.let {q->if(q.opt("value") is Number)put(path+"valueQuantity",HealthValue(q.getDouble("value"),q.optString("unit",q.optString("code","value"))))}
                    for(k in listOf("valueInteger","valueDecimal"))if(obj.opt(k) is Number)put(path+k,HealthValue(obj.getDouble(k),"value"))
                    obj.optJSONArray("component")?.let {visit(it,path+"component.")}
                }
                is JSONArray->for(i in 0 until obj.length())visit(obj.get(i),path+"$i.")
            }
        }
        visit(o,"")
    }
}
