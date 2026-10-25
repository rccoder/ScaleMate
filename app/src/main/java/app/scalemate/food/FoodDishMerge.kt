package app.scalemate.food

internal fun FoodBatch.mergeDish(ids:Set<String>,name:String,portion:String):FoodBatch {
    require(kind=="photo"&&status=="review"&&editingItemId==null)
    require(ids.size>=2&&results.count {it.id in ids}==ids.size)
    require(name.trim().length in 1..120&&portion.trim().length in 1..100)
    val selected=results.filter {it.id in ids}
    require(selected.all {it.copy(needsPortionReview=false).valid()})
    val parts=selected.map {it.reuse()}
    val nutrients=Nutrients.sum(parts.map {it.nutrients})
    require(nutrients.valid()&&nutrients.hasValue())
    val grams=if(parts.all {it.grams!=null})parts.sumOf {it.grams!!} else null
    fun text(value:(FoodItem)->String)=parts.mapNotNull {f->value(f).takeIf {it.isNotBlank()}?.let {"${f.name}: $it"}}.joinToString("\n")
    val contexts=parts.mapNotNull {it.estimateContext}
    val personal=contexts.size==parts.size&&contexts.all {it.resultMode=="personal"}
    // Normalize the already calculated personal amounts before combining them.
    val merged=FoodItem(
        name=name.trim(),portion=portion.trim(),grams=grams,base=nutrients,source="ai",
        needsPortionReview=true,photos=parts.flatMap {it.photos}.distinct(),
        assumptions=text {f->listOf(f.portion,f.assumptions).filter {it.isNotBlank()}.joinToString("; ")},
        eatingAdvice=text {it.eatingAdvice},nextTimeAdvice=text {it.nextTimeAdvice},
        positiveFeedback=if(parts.none {it.eatingAdvice.isNotBlank()||it.nextTimeAdvice.isNotBlank()})text {it.positiveFeedback} else "",
        estimateContext=FoodEstimateContext(
            shareCount=shareCount,tags=tags.toList(),description=description,
            resultMode=if(personal)"personal" else "legacy",
            summary=text {it.estimateContext?.summary.orEmpty()},
            interpretation=text {it.estimateContext?.interpretation.orEmpty()},
            warning=text {it.estimateContext?.warning.orEmpty()},
            reportedNutrients=if(personal)Nutrients.sum(contexts.map {it.reportedNutrients}) else Nutrients(),
            reportedGrams=if(personal&&contexts.all {it.reportedGrams!=null})contexts.sumOf {it.reportedGrams!!} else null
        )
    )
    require(merged.copy(needsPortionReview=false).valid())
    val first=results.indexOfFirst {it.id in ids}
    return copy(results=results.flatMapIndexed {index,item->when {
        index==first->listOf(merged)
        item.id in ids->emptyList()
        else->listOf(item)
    }})
}
