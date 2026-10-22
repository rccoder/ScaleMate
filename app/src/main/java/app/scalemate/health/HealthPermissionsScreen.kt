package app.scalemate.health

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
    var showMore by rememberSaveable {mutableStateOf(false)}
    val routeConsent=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        vm.acceptRoute(if(result.resultCode==Activity.RESULT_OK)result.data?.getParcelableExtra(android.health.connect.HealthConnectManager.EXTRA_EXERCISE_ROUTE,android.health.connect.datatypes.ExerciseRoute::class.java) else null)
    }
    LaunchedEffect(Unit){vm.reload();resetScroll()}
    BackHandler{back()}
    Column(verticalArrangement=Arrangement.spacedBy(18.dp)) {
        Text(stringResource(R.string.health_permission_settings_summary),color=MaterialTheme.colorScheme.onSurfaceVariant)

        SettingsGroup(stringResource(R.string.health_required_categories)) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.health_required_categories_note),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    val visible=if(showMore)HealthCatalog.kinds else HealthCatalog.kinds.filter {it.id in HealthCatalog.common}
                    visible.forEach {kind->
                        val selected=kind.id in state.categories
                        FilterChip(
                            selected=selected,
                            enabled=!state.busy&&state.permissions[kind.id]!="unsupported",
                            onClick={vm.categories(if(selected)state.categories-kind.id else state.categories+kind.id)},
                            label={Text(kind.label(chinese))},
                            leadingIcon=if(selected)({Icon(Icons.Default.Check,null,Modifier.size(FilterChipDefaults.IconSize))}) else null
                        )
                    }
                    TextButton(onClick={showMore=!showMore}){Text(stringResource(if(showMore==true)R.string.health_fewer_categories else R.string.health_more_categories))}
                }
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
