package app.scalemate

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity

class PrivacyActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text=getString(R.string.privacy_text);textSize=18f;setPadding(32,80,32,32) })
    }
}
