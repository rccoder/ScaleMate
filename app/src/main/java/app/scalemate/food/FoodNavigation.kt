package app.scalemate.food

internal fun foodEntryNeedsChoice(draft:FoodDraft?):Boolean = draft?.isEmpty()==false

internal data class FoodBackAction(val page:String?=null,val discardBatch:Boolean=false,val confirmDiscard:Boolean=false)

internal fun foodBackAction(page:String,batch:FoodBatch?,manualReturn:String="editor",servicesReturn:String="editor"):FoodBackAction = when(page) {
    "photo"->if(batch?.results?.isNotEmpty()==true)FoodBackAction(confirmDiscard=true)else FoodBackAction("editor",discardBatch=true)
    "review"->FoodBackAction(when(batch?.kind){"manual"->manualReturn;"edit"->"editor";else->"picker"},discardBatch=true)
    "picker"->FoodBackAction("editor")
    "services"->FoodBackAction(servicesReturn)
    else->FoodBackAction()
}

internal fun foodDraftPage(draft:FoodDraft?):String = when {
    draft==null||draft.isEmpty()->"home"
    draft.batch?.kind=="photo"->"photo"
    draft.batch!=null->"review"
    else->"editor"
}

internal enum class FoodSaveAction { CONFIRM_BATCH, SAVE_MEAL }

internal fun foodSaveAction(page:String?,draft:FoodDraft?,busy:Boolean,saving:Boolean):FoodSaveAction? {
    if(draft==null||busy||saving)return null
    return when {
        page in listOf("photo","review")&&draft.batch?.status=="review"&&
            draft.batch.results.any {it.hasDraftContent}->FoodSaveAction.CONFIRM_BATCH
        page=="editor"&&draft.batch==null&&draft.meal.items.isNotEmpty()->FoodSaveAction.SAVE_MEAL
        else->null
    }
}
