package com.syvolotskyi.getphotomanager

import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Matrix
import android.support.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*

fun Context.getImageIntent(): Intent {

    // Determine Uri of camera image to save.
    val outputFileUri = getCaptureImageUri()

    val allIntents = mutableListOf<Intent>()
    val packageManager = packageManager

    // collect all camera intents
    val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
    val listCam = packageManager.queryIntentActivities(captureIntent, 0)
    for (res in listCam) {
        val intent = Intent(captureIntent)
        intent.component = ComponentName(res.activityInfo.packageName, res.activityInfo.name)
        intent.`package` = res.activityInfo.packageName
        if (outputFileUri != null) {
            if (!isSetExternalContentUri()) {
                intent.putExtra(MediaStore.EXTRA_OUTPUT, outputFileUri)
            } else {
                intent.putExtra(MediaStore.EXTRA_OUTPUT, outputFileUri.path)
            }
        }
        allIntents.add(intent)
    }

    // collect all gallery intents
    val galleryIntent = Intent(Intent.ACTION_GET_CONTENT)
    galleryIntent.type = "image/*"
    val listGallery = packageManager.queryIntentActivities(galleryIntent, 0)
    for (res in listGallery) {
        val intent = Intent(galleryIntent)
        intent.component = ComponentName(res.activityInfo.packageName, res.activityInfo.name)
        intent.`package` = res.activityInfo.packageName
        allIntents.add(intent)
    }

    // the main intent is the last in the list (fucking android) so pickup the useless one
    var mainIntent: Intent = allIntents[allIntents.size - 1]
    for (intent in allIntents) {
        if (intent.component!!.className == "com.android.documentsui.DocumentsActivity") {
            mainIntent = intent
            break
        }
    }
    allIntents.remove(mainIntent)

    // Create a chooser from the main intent
    val chooserIntent = Intent.createChooser(mainIntent, "Select source")

    // Add all other intents
    chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, allIntents.toTypedArray())

    return chooserIntent
}


/**
 * Get URI to image received from capture by camera.
 */
private fun Context.getCaptureImageOutputDCIMUri() =
        getExternalFilesDir(Environment.DIRECTORY_DCIM)?.let { Uri.fromFile(File(it, "photo.png")) }

private fun Context.getCaptureImageUri(): Uri? {
    return if (!isSetExternalContentUri()) {
        getCaptureImageOutputDCIMUri()
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
}

fun Context.getImageFromIntent(intent: Intent?): File? {
    val fileName = getFileName()
    val imageUri: Uri? = if (!isSetExternalContentUri() || getPickImageResultUri(intent)?.scheme == "content") {
        getPickImageResultUri(intent)
    } else {
        getUri()
    }

    var bitmap: Bitmap? = null
    if (imageUri != null) {
        try {
            bitmap = MediaStore.Images.Media.getBitmap(contentResolver, imageUri)
            saveFile(bitmap, fileName)
            bitmap = rotateImageIfRequired(bitmap, fileName.absolutePath, imageUri)
        } catch (e: Exception) {
            Timber.e(e)
        }
    } else {
        bitmap = intent?.extras?.get("data") as Bitmap?
    }
    return bitmap?.let { getResizedBitmap(it, 512) }?.let {
        saveFile(it, fileName)
        it.recycle()
        fileName
    }
}

fun Context.getUri(): Uri {
    var id = 0L
    val projections = arrayOf(MediaStore.Images.ImageColumns._ID,
            MediaStore.Images.ImageColumns.ORIENTATION,
            MediaStore.Images.ImageColumns.SIZE)
    val cursor = contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projections, null, null, null)
    if (cursor.moveToLast()) {
        id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns._ID))
    }
    cursor.close()
    return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
}

@Throws(IOException::class)
private fun Context.rotateImageIfRequired(img: Bitmap, path: String, uri: Uri): Bitmap {

    val ei = ExifInterface(path)
    val orientation = ei.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    val degree = getOrientation(uri)
    return when (orientation) {
        ExifInterface.ORIENTATION_UNDEFINED -> {
            if (degree > 0)
                rotateImage(img, degree)
            else
                img
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> rotateImage(img, 90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> rotateImage(img, 180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> rotateImage(img, 270f)
        else -> img
    }
}

private fun rotateImage(img: Bitmap, degree: Float): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(degree)
    val rotatedImg = Bitmap.createBitmap(img, 0, 0, img.width, img.height, matrix, true)
    img.recycle()
    return rotatedImg
}

fun getResizedBitmap(image: Bitmap, maxSize: Int): Bitmap {
    var width = image.width
    var height = image.height

    val bitmapRatio = width.toFloat() / height.toFloat()
    if (bitmapRatio > 0) {
        width = maxSize
        height = (width / bitmapRatio).toInt()
    } else {
        height = maxSize
        width = (height * bitmapRatio).toInt()
    }
    val out = Bitmap.createScaledBitmap(image, width, height, true)
    image.recycle()
    return out
}

private fun Context.getOrientation(path: Uri): Float {
    val cursor: Cursor
    val projections = arrayOf(MediaStore.Images.ImageColumns._ID,
            MediaStore.Images.ImageColumns.ORIENTATION,
            MediaStore.Images.ImageColumns.SIZE)
    var degrees = 0f

    if (path.scheme == "content") {
        cursor = contentResolver.query(path,
                projections, null, null, null)
        if (cursor.moveToFirst()) {
            degrees = cursor.getFloat(cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.ORIENTATION))
        }
    } else {
        val fileSize = File(path.path).length()
        cursor = contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projections, null, null, null)
        if (cursor.moveToLast()) {
            val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.SIZE))

            //Extra check to make sure that we are getting the orientation from the proper file
            if (fileSize == size) {
                degrees = cursor.getFloat(cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.ORIENTATION))
            }
        }
    }

    cursor.close()

    return degrees
}

/**
 * Get the URI of the selected image from [.getPickImageChooserIntent].
 *
 * Will return the correct URI for camera and gallery image.
 *
 * @param data the returned data of the activity result
 */
private fun Context.getPickImageResultUri(data: Intent?): Uri? {
    return data?.data ?: getCaptureImageOutputDCIMUri()
}

private fun Context.getFileName(): File {
    val dir = getImagesCacheDir()
    val fileIndex = Date().time.toString()
    return File(dir, fileIndex + ".jpeg")
}

private fun Context.getImagesCacheDir() = File(cacheDir, "images").apply { mkdirs() }

private fun saveFile(b: Bitmap, file: File) {
    var fos: FileOutputStream? = null
    try {
        fos = FileOutputStream(file.absolutePath)
        b.compress(Bitmap.CompressFormat.JPEG, 90, fos)
        fos.flush()
        fos.close()
    } catch (e: Exception) {
        Timber.e(e)
    }
}

private fun isSetExternalContentUri(): Boolean {
    var setExternalContentUri = false

    // NOTE: Do NOT SET: intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraPicUri)
    // on Samsung Galaxy S2/S3/.. for the following reasons:
    // 0.) faking android
    // 1.) it will break the correct picture orientation
    // 2.) the photo will be stored in two locations (the given path and, additionally, in the MediaStore)
    val manufacturer = Build.MANUFACTURER.toLowerCase(Locale.ENGLISH)
    val model = Build.MODEL.toLowerCase(Locale.ENGLISH)

    if (manufacturer.contains("samsung") && model.contains("galaxy nexus")) { //TESTED
        setExternalContentUri = true
    }

    if (manufacturer.contains("samsung") && model.contains("sm-g950")) { //Samsung Galaxy S8
        setExternalContentUri = true
    }

    if (manufacturer.contains("samsung") && model.contains("sm-g955")) { //Samsung Galaxy S8+
        setExternalContentUri = true
    }

    return setExternalContentUri
}
