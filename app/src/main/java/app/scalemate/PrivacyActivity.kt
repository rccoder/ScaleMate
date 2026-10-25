package app.scalemate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

class PrivacyActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings=getSharedPreferences("settings",0)
        val dark=settings.getBoolean("dark",false)
        val dynamic=settings.getBoolean("dynamic",false)
        WindowCompat.getInsetsController(window,window.decorView).apply {
            isAppearanceLightStatusBars=!dark
            isAppearanceLightNavigationBars=!dark
        }
        setContent {MaterialTheme(colorScheme=scaleMateColorScheme(dark,dynamic)){PrivacyScreen(onBack=::finish)}}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PrivacyScreen(onBack:()->Unit) {
    val colors=MaterialTheme.colorScheme
    Scaffold(
        containerColor=colors.background,
        topBar={TopAppBar(title={Text(stringResource(R.string.privacy),fontWeight=FontWeight.SemiBold)},navigationIcon={
            IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Filled.ArrowBack,stringResource(R.string.privacy_back))}
        })}
    ) {padding->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Surface(shape=RoundedCornerShape(28.dp),color=lerp(colors.surface,colors.primaryContainer,.65f),modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    Surface(shape=CircleShape,color=colors.surface.copy(alpha=.7f)) {
                        Icon(Icons.Default.Lock,null,Modifier.padding(12.dp).size(24.dp),tint=colors.primary)
                    }
                    Text(stringResource(R.string.privacy_intro_title),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold,color=colors.onPrimaryContainer)
                    Text(stringResource(R.string.privacy_intro_body),style=MaterialTheme.typography.bodyLarge,lineHeight=25.sp,color=colors.onPrimaryContainer)
                }
            }
            PrivacySection(R.string.privacy_local_title,R.string.privacy_local_body)
            PrivacySection(R.string.privacy_bluetooth_title,R.string.privacy_bluetooth_body)
            PrivacySection(R.string.privacy_health_title,R.string.privacy_health_body)
            if(BuildConfig.HEALTH_AI_ENABLED) {
                PrivacySection(R.string.privacy_health_analysis_title,R.string.privacy_health_analysis_body)
                PrivacySection(R.string.privacy_health_history_title,R.string.privacy_health_history_body)
            }
            PrivacySection(R.string.privacy_ai_title,R.string.privacy_ai_body,R.string.privacy_ai_detail)
            PrivacySection(R.string.privacy_update_title,R.string.privacy_update_body)
            PrivacySection(R.string.privacy_control_title,R.string.privacy_control_body)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable private fun PrivacySection(title:Int,body:Int,detail:Int?=null) {
    val colors=MaterialTheme.colorScheme
    Surface(shape=RoundedCornerShape(22.dp),color=lerp(colors.surface,colors.surfaceVariant,.35f),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(title),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,color=colors.onSurface)
            Text(stringResource(body),style=MaterialTheme.typography.bodyMedium,lineHeight=24.sp,color=colors.onSurfaceVariant)
            if(detail!=null) {
                HorizontalDivider(color=colors.outlineVariant.copy(alpha=.6f))
                Text(stringResource(detail),style=MaterialTheme.typography.bodySmall,lineHeight=22.sp,color=colors.onSurfaceVariant)
            }
        }
    }
}
