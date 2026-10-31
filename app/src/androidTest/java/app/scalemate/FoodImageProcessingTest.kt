package app.scalemate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.scalemate.food.FoodImages
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoodImageProcessingTest {
    @Test fun importDownscalesLargeCameraImageBeforeUpload() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source=File(context.cacheDir,"large-food-source.jpg")
        val bitmap=Bitmap.createBitmap(4000,3000,Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(220,180,120))
        }
        try {
            source.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,100,it))}
            val imported=FoodImages.import(context,Uri.fromFile(source))
            try {
                val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                BitmapFactory.decodeFile(imported.absolutePath,bounds)
                assertEquals(1024,maxOf(bounds.outWidth,bounds.outHeight))
                assertTrue(imported.length() in 1..FoodImages.MAX_IMAGE_BYTES)
            }finally {imported.delete()}
        }finally {
            bitmap.recycle()
            source.delete()
        }
    }

    @Test fun importedBaselinePhotoFitsCompactUploadBudget() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val source=File(context.cacheDir,"baseline-food-source.png")
        instrumentation.context.assets.open("food-prompt-baseline/kung-pao-chicken-with-rice.png").use {input->
            source.outputStream().use(input::copyTo)
        }
        try {
            val imported=FoodImages.import(context,Uri.fromFile(source))
            try {
                val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                BitmapFactory.decodeFile(imported.absolutePath,bounds)
                assertEquals(1024,maxOf(bounds.outWidth,bounds.outHeight))
                assertTrue("Expected compact JPEG, got ${imported.length()} bytes",imported.length()<300_000)
            }finally {imported.delete()}
        }finally {source.delete()}
    }
}
