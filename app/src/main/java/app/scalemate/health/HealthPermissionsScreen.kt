package app.scalemate.health

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scalemate.R
import app.scalemate.SettingsDivider
import app.scalemate.SettingsGroup
import app.scalemate.SettingsRow

@OptIn(ExperimentalLayoutApi::class)
@Composable fun HealthPermissionsScreen(
    vm:HealthViewModel,
    authorize:(Set<String>)->Unit,
    openHealthSettings:()->Unit,
    back:()->Unit,
    resetScroll:()->Unit={}
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val chinese=LocalConfiguration.current.locales[0].language=="zh"
    var expanded by rememberSaveable {mutableStateOf<HealthPrivacyGroup?>(HealthPrivacyGroup.ACTIVITY)}
    val routeConsent=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        vm.acceptRoute(if(result.resultCode==Activity.RESULT_OK)result.data?.getParcelableExtra(android.health.connect.HealthConnectManager.EXTRA_EXERCISE_ROUTE,android.health.connect.datatypes.ExerciseRoute::class.java) else null)
    }
    LaunchedEffect(Unit){vm.reload();resetScroll()}
    BackHandler{back()}
    Column(verticalArrangement=Arrangement.spacedBy(18.dp)) {
        Text(stringResource(R.string.health_permission_settings_summary),color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.health_privacy_overview),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)

        SettingsGroup(stringResource(R.string.health_required_categories)) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.health_required_categories_note),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                for(group in HealthPrivacyGroup.entries)HealthPrivacySection(group,state,chinese,expanded==group,onToggle={expanded=if(expanded==group)null else group},vm)
                Text(stringResource(R.string.health_category_selection_summary,state.categories.size,state.categories.count {state.permissions[it]=="ready"}),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled=!state.busy,onClick={vm.categories(HealthCatalog.common.filter {state.permissions[it]!="unsupported"}.toSet())}){Text(stringResource(R.string.health_select_common))}
                    TextButton(enabled=!state.busy,onClick={vm.categories(emptySet())}){Text(stringResource(R.string.health_select_none))}
                }
                Button(
                    enabled=!state.busy&&state.categories.isNotEmpty(),
                    onClick={
                        authorize(state.categories.mapNotNull {id->HealthCatalog.kinds.find {it.id==id}}.flatMap {kind->
                            if(kind.id=="ExerciseRoute")listOf("android.permission.health.READ_EXERCISE")+listOfNotNull(kind.permission.takeIf {runCatching {context.packageManager.getPermissionInfo(it,0)}.isSuccess})
                            else listOf(kind.permission)
                        }.toSet())
                    },
                    modifier=Modifier.fillMaxWidth()
                ){Text(stringResource(R.string.health_grant))}
                Text(stringResource(R.string.health_permission_note),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if("ExerciseRoute" in state.categories&&state.routeRequests.isNotEmpty()) {
            SettingsGroup(stringResource(R.string.health_route_permissions)) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.health_route_consent_note),style=MaterialTheme.typography.bodySmall)
                    state.routeRequests.forEach {request->
                        OutlinedButton(
                            enabled=!state.busy,
                            onClick={
                                vm.beginRoute(request)
                                try {routeConsent.launch(Intent(android.health.connect.HealthConnectManager.ACTION_REQUEST_EXERCISE_ROUTE).putExtra(android.health.connect.HealthConnectManager.EXTRA_SESSION_ID,request.platformId))}
                                catch(_:Exception){vm.acceptRoute(null)}
                            },
                            modifier=Modifier.fillMaxWidth()
                        ){Text(stringResource(R.string.health_route_allow))}
                    }
                }
            }
        }

        SettingsGroup(stringResource(R.string.health_optional_permissions)) {
            SettingsRow(
                stringResource(R.string.health_history),
                "${stringResource(if(state.historyGranted)R.string.health_history_enabled else R.string.health_history_disabled)} · ${stringResource(R.string.health_history_optional)}",
                if(state.historySupported&&!state.historyGranted)({authorize(setOf(HealthCatalog.HISTORY))}) else null
            )
        }

        OutlinedButton(onClick=openHealthSettings,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.health_open_settings))}
        Text(stringResource(R.string.health_permissions_saved),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun HealthPrivacySection(group:HealthPrivacyGroup,state:HealthState,chinese:Boolean,isExpanded:Boolean,onToggle:()->Unit,vm:HealthViewModel) {
    val kinds=HealthCatalog.kinds.filter {HealthCatalog.privacyGroup(it)==group}
    val selected=kinds.count {it.id in state.categories}
    val allowed=kinds.count {it.id in state.categories&&state.permissions[it.id]=="ready"}
    val summary=if(selected==0)stringResource(R.string.health_privacy_none_selected) else stringResource(R.string.health_privacy_selected,selected,allowed)
    val title=when(group){HealthPrivacyGroup.ACTIVITY->R.string.health_privacy_activity;HealthPrivacyGroup.BODY->R.string.health_privacy_body;HealthPrivacyGroup.CLINICAL->R.string.health_privacy_clinical}
    val note=when(group){HealthPrivacyGroup.ACTIVITY->R.string.health_privacy_activity_note;HealthPrivacyGroup.BODY->R.string.health_privacy_body_note;HealthPrivacyGroup.CLINICAL->R.string.health_privacy_clinical_note}
    Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=if(group==HealthPrivacyGroup.CLINICAL).45f else .55f),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(title),style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
                    Text(summary,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick=onToggle){Text(if(isExpanded)"−" else "+",style=MaterialTheme.typography.titleLarge)}
            }
            Text(stringResource(note),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(group==HealthPrivacyGroup.CLINICAL)Text(stringResource(R.string.health_privacy_sensitive_warning),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            if(isExpanded) {
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    kinds.forEach {kind->
                        val checked=kind.id in state.categories
                        FilterChip(
                            selected=checked,
                            enabled=!state.busy&&state.permissions[kind.id]!="unsupported",
                            onClick={vm.categories(if(checked)state.categories-kind.id else state.categories+kind.id)},
                            label={Text(kind.label(chinese),maxLines=1,overflow=TextOverflow.Ellipsis)},
                            leadingIcon=if(checked)({Icon(Icons.Default.Check,null,Modifier.size(FilterChipDefaults.IconSize))}) else null
                        )
                    }
                }
            }
        }
    }
}
