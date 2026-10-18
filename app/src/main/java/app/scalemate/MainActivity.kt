package app.scalemate

import android.app.LocaleManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import app.scalemate.platform.HealthOutput
import app.scalemate.food.FoodViewModel
import app.scalemate.food.NutritionOutput

class MainActivity: ComponentActivity() {
    private lateinit var model: ScaleViewModel
    private lateinit var foodModel: FoodViewModel
    private val nutritionPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){foodModel.refreshPermissions()}
    private var afterBluetooth: (() -> Unit)? = null
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if(result.values.all { it } && result.isNotEmpty()) afterBluetooth?.invoke() else model.message(R.string.bluetooth_error)
        afterBluetooth=null
    }
    private val healthPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.refreshPermissions();if(!granted)model.message(R.string.permission_denied)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        model=ViewModelProvider(this)[ScaleViewModel::class.java]
        foodModel=ViewModelProvider(this)[FoodViewModel::class.java]
        setContent { ScaleMateApp(model,foodModel,authorizeNutrition={nutritionPermission.launch(NutritionOutput.WRITE)},
            bluetooth = { action ->
                val permissions=arrayOf("android.permission.BLUETOOTH_SCAN","android.permission.BLUETOOTH_CONNECT")
                if(permissions.all { checkSelfPermission(it)==android.content.pm.PackageManager.PERMISSION_GRANTED }) action()
                else {afterBluetooth=action;bluetoothPermission.launch(permissions)}
            },
            authorize = { healthPermission.launch(HealthOutput.WRITE) },
            healthSettings = { open(Intent("android.health.connect.action.HEALTH_HOME_SETTINGS")) },
            googleApp = {
                val target=listOf("com.google.android.apps.fitness","com.fitbit.FitbitMobile").firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
                open(target ?: Intent(Intent.ACTION_VIEW,Uri.parse("https://play.google.com/store/apps/details?id=com.fitbit.FitbitMobile")))
            },
            language = { tag -> getSystemService(LocaleManager::class.java).applicationLocales=LocaleList.forLanguageTags(tag) },
            privacy = {open(Intent(this,PrivacyActivity::class.java))}) }
    }
    private fun open(intent: Intent) {try {startActivity(intent)}catch(_: Exception){model.message(R.string.open_error)}}
    override fun onResume() {super.onResume();if(::model.isInitialized){model.refreshPermissions();model.foreground(true);foodModel.refreshPermissions()}}
    override fun onPause() {if(::model.isInitialized)model.foreground(false);super.onPause()}
}
