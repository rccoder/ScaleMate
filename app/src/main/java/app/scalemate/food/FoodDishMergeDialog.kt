package app.scalemate.food

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.scalemate.R
import java.text.NumberFormat

@Composable internal fun FoodDishMergeDialog(batch:FoodBatch,onDismiss:()->Unit,onMerge:(Set<String>,String,String)->Unit) {
    var selected by rememberSaveable(batch.id){mutableStateOf(emptyList<String>())}
    var name by rememberSaveable(batch.id){mutableStateOf("")}
    val defaultPortion=stringResource(R.string.food_merge_default_portion)
    var portion by rememberSaveable(batch.id){mutableStateOf(defaultPortion)}
    val ids=selected.toSet()
    val foods=batch.results.filter {it.id in ids}
    val total=Nutrients.sum(foods.map {it.nutrients})
    val valid=remember(batch,selected,name,portion){runCatching {batch.mergeDish(ids,name,portion)}.isSuccess}
    val format=remember {NumberFormat.getNumberInstance().apply {maximumFractionDigits=1}}
    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text(stringResource(R.string.food_merge_dish))},
        text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.food_merge_hint))
            batch.results.forEach {food->
                Row(Modifier.fillMaxWidth().toggleable(value=food.id in ids,role=Role.Checkbox,onValueChange={checked->selected=if(checked)selected+food.id else selected-food.id}).padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(checked=food.id in ids,onCheckedChange=null)
                    Column(Modifier.weight(1f).padding(start=8.dp)) {
                        Text(food.name,style=MaterialTheme.typography.bodyLarge)
                        Text(food.portion,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            OutlinedTextField(value=name,onValueChange={name=it.take(120)},label={Text(stringResource(R.string.food_merge_name))},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=portion,onValueChange={portion=it.take(100)},label={Text(stringResource(R.string.food_portion))},modifier=Modifier.fillMaxWidth())
            if(foods.size>=2) {
                Text(stringResource(R.string.food_merge_total),style=MaterialTheme.typography.titleSmall)
                Text(total.kcal?.let {"${format.format(it)} kcal"} ?: stringResource(R.string.food_merge_unknown),style=MaterialTheme.typography.headlineSmall)
                NutritionRow(total)
                if(listOf(total.kcal,total.protein,total.carbs,total.fat).any {it==null})Text(stringResource(R.string.food_nutrition_partial),style=MaterialTheme.typography.bodySmall)
                if(!total.valid()||!total.hasValue()||foods.any {!it.copy(needsPortionReview=false).valid()})Text(stringResource(R.string.food_merge_invalid),color=MaterialTheme.colorScheme.error)
            }
        }},
        confirmButton={TextButton(enabled=valid,onClick={onMerge(ids,name,portion)}){Text(stringResource(R.string.food_merge_confirm))}},
        dismissButton={TextButton(onClick=onDismiss){Text(stringResource(R.string.cancel))}}
    )
}
