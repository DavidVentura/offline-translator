/*
 * Copyright (C) 2024 David V
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.davidv.translator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max

class ImageProcessor(
  private val context: Context,
  private val filePathManager: FilePathManager,
) {
  fun loadCatalog(): LanguageCatalog? = filePathManager.loadCatalog()

  fun loadBitmapFromUri(uri: Uri): Bitmap =
    context.contentResolver.openInputStream(uri)?.use { inputStream ->
      BitmapFactory.decodeStream(inputStream)
    } ?: throw IllegalArgumentException("Cannot load bitmap from URI: $uri")

  fun deleteTemporaryImageUri(uri: Uri): Boolean =
    try {
      when (uri.scheme) {
        "content" -> context.contentResolver.delete(uri, null, null) > 0
        "file" -> uri.path?.let(::File)?.delete() == true
        else -> false
      }
    } catch (e: Exception) {
      Log.w("ImageProcessor", "Failed to delete temporary image URI: $uri", e)
      false
    }

  fun downscaleImage(
    bitmap: Bitmap,
    maxSize: Int,
  ): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    val longestSide = max(width, height)

    if (longestSide <= maxSize) {
      return bitmap
    }

    val scale = maxSize.toFloat() / longestSide.toFloat()
    val newWidth = (width * scale).toInt()
    val newHeight = (height * scale).toInt()
    Log.i("ImageProcessor", "Resized to $newWidth x $newHeight")
    return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
  }

  fun correctImageOrientation(
    uri: Uri,
    bitmap: Bitmap,
  ): Bitmap {
    return try {
      context.contentResolver.openInputStream(uri)?.use { inputStream ->
        val exif = ExifInterface(inputStream)
        val orientation =
          exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
          )

        val matrix = Matrix()
        when (orientation) {
          ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
          ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
          ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
          ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
          ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
          ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
          }
          ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
          }
          else -> return bitmap
        }

        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      } ?: bitmap
    } catch (e: Exception) {
      Log.e("ImageProcessor", "Error correcting image orientation", e)
      bitmap
    }
  }
}
