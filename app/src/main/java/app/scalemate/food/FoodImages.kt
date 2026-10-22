package app.scalemate.food

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.Bitmap
import android.net.Uri
import java.io.File

object FoodImages {
    /**
     * Decode/re-encode to apply orientation, flatten Live Photos to their still frame, and remove
     * EXIF/location metadata before any upload.
     */
    fun import(context: Context,uri: Uri): File {
        val dir=File(context.filesDir,"food_photos").apply {mkdirs()}
        val target=File(dir,"${newFoodId()}.jpg")
        var bitmap: Bitmap?=null
        try {
            bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver,uri)) {decoder,info,_->
                val scale=(maxOf(info.size.width,info.size.height)/1280.0).coerceAtLeast(1.0)
                decoder.setTargetSize((info.size.width/scale).toInt().coerceAtLeast(1),(info.size.height/scale).toInt().coerceAtLeast(1))
                decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            }
            target.outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.JPEG,75,it))}
            require(target.length() in 1..MAX_IMAGE_BYTES)
            return target
        }catch(e:Exception){target.delete();throw e}finally {bitmap?.recycle()}
    }
    const val MAX_PHOTOS=6
    const val MAX_IMAGE_BYTES=1_500_000L
    // Bound the encoded request, not only the sum of JPEG files. Base64 adds roughly one third.
    const val MAX_UPLOAD_BYTES=9_000_000L
    internal fun encodedRequestApproximation(bytes:Long)=((bytes+2)/3)*4
    fun thumbnail(context:Context,path:String):String {
        val source=File(path);if(source.parentFile?.name=="food_thumbs")return path
        if(!source.exists())return ""
        val dir=File(context.filesDir,"food_thumbs").apply {mkdirs()}
        val target=File(dir,source.nameWithoutExtension+".jpg")
        if(target.exists())return target.absolutePath
        val bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) {decoder,info,_->
            val scale=(maxOf(info.size.width,info.size.height)/512.0).coerceAtLeast(1.0)
            decoder.setTargetSize((info.size.width/scale).toInt().coerceAtLeast(1),(info.size.height/scale).toInt().coerceAtLeast(1));decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {target.outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.JPEG,75,it))}}catch(e:Exception){target.delete();throw e}finally {bitmap.recycle()}
        return target.absolutePath
    }
    fun cleanup(context: Context,keep:Set<String>) {
        listOf("food_photos","food_thumbs").forEach {folder->
            File(context.filesDir,folder).listFiles()?.filter {it.absolutePath !in keep&&System.currentTimeMillis()-it.lastModified()>60_000}?.forEach {it.delete()}
        }
        File(context.cacheDir,"food_capture").listFiles()?.filter {System.currentTimeMillis()-it.lastModified()>24*60*60*1000}?.forEach {it.delete()}
    }
}
