package app.scalemate

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable internal fun SettingsGroup(title:String,content:@Composable ColumnScope.()->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,Modifier.padding(start=16.dp),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.35f)) {Column(Modifier.fillMaxWidth(),content=content)}
    }
}
@Composable internal fun SettingsDivider() {HorizontalDivider(Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.onSurface.copy(alpha=.08f))}
@Composable internal fun SettingsRow(title:String,summary:String,onClick:(()->Unit)?=null) =
    SettingsRow(title,summary,showBadge=false,onClick=onClick)

@Composable internal fun SettingsRow(title:String,summary:String,showBadge:Boolean,onClick:(()->Unit)?=null) {
    Row(Modifier.fillMaxWidth().then(if(onClick!=null)Modifier.clickable(role=Role.Button,onClick=onClick) else Modifier).padding(horizontal=16.dp,vertical=14.dp).heightIn(min=36.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(title,style=MaterialTheme.typography.bodyLarge,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                if(showBadge)Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.error, RoundedCornerShape(50)))
            }
            if(summary.isNotBlank())Text(summary,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
        if(onClick!=null)Text("›",style=MaterialTheme.typography.headlineSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable internal fun SettingsToggle(title:String,summary:String,value:Boolean,enabled:Boolean=true,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value=value,enabled=enabled,role=Role.Switch,onValueChange=onChange).padding(horizontal=16.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,style=MaterialTheme.typography.bodyLarge,fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.onSurface.copy(alpha=if(enabled)1f else .6f))
            Text(summary,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value,onCheckedChange=null,enabled=enabled,colors=settingsSwitchColors())
    }
}

@Composable internal fun settingsSwitchColors()=SwitchDefaults.colors(
    uncheckedTrackColor=MaterialTheme.colorScheme.surfaceVariant,
    uncheckedBorderColor=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.45f),
    uncheckedThumbColor=MaterialTheme.colorScheme.onSurfaceVariant
)
