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

package dev.davidv.translator.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import dev.davidv.translator.R
import dev.davidv.translator.TranslatorMessage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ImageCaptureHandler(
  onMessage: (TranslatorMessage) -> Unit,
  showImageSourceSheet: Boolean,
  onDismissImageSourceSheet: () -> Unit,
  showFilePickerInImagePicker: Boolean = true,
) {
  val context = LocalContext.current

  // Create temporary file for camera capture
  val cameraImageUri =
    remember {
      val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
      val imageFile = File(context.cacheDir, "camera_image_$timeStamp.jpg")
      try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", imageFile)
      } catch (e: IllegalArgumentException) {
        Log.e("ImageCapture", "Failed to create camera image URI")
        Uri.EMPTY
      }
    }

  val cropImage =
    rememberLauncherForActivityResult(CropImageContract()) { result ->
      if (result.isSuccessful) {
        val croppedUri = result.uriContent
        if (croppedUri != null) {
          Log.d("ImageCrop", "Image cropped: $croppedUri")
          onMessage(TranslatorMessage.SetImageUri(croppedUri))
        } else {
          Log.d("ImageCrop", "Crop successful but no URI returned")
        }
      } else {
        val exception = result.error
        Log.d("ImageCrop", "Crop cancelled or failed: ${exception?.message}")
      }
    }

  val takePictureIntent =
    rememberLauncherForActivityResult(
      ActivityResultContracts.StartActivityForResult(),
    ) { result ->
      if (result.resultCode == android.app.Activity.RESULT_OK) {
        Log.d("Camera", "Photo captured: $cameraImageUri")
        cropImage.launch(
          CropImageContractOptions(
            uri = cameraImageUri,
            cropImageOptions = CropImageOptions(),
          ),
        )
      } else {
        Log.d("Camera", "Photo capture cancelled or failed")
      }
    }

  val pickMedia =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
      if (uri != null) {
        Log.d("PhotoPicker", "Selected URI: $uri")
        cropImage.launch(
          CropImageContractOptions(
            uri = uri,
            cropImageOptions = CropImageOptions(),
          ),
        )
      } else {
        Log.d("PhotoPicker", "No media selected")
      }
    }

  val pickFromGallery =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (result.resultCode == android.app.Activity.RESULT_OK) {
        val imageUri = result.data?.data
        if (imageUri != null) {
          Log.d("Gallery", "Selected URI: $imageUri")
          cropImage.launch(
            CropImageContractOptions(
              uri = imageUri,
              cropImageOptions = CropImageOptions(),
            ),
          )
        } else {
          Log.d("Gallery", "No image selected")
        }
      }
    }

  val pickFromFiles =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
      if (uri != null) {
        Log.d("FilePicker", "Selected URI: $uri")
        cropImage.launch(
          CropImageContractOptions(
            uri = uri,
            cropImageOptions = CropImageOptions(),
          ),
        )
      } else {
        Log.d("FilePicker", "No file selected")
      }
    }

  // Image source selection bottom sheet
  if (showImageSourceSheet) {
    ImageSourceBottomSheet(
      onDismiss = onDismissImageSourceSheet,
      showFilePickerOption = showFilePickerInImagePicker,
      onCameraClick = {
        onDismissImageSourceSheet()
        val cameraIntent =
          Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
          }
        try {
          takePictureIntent.launch(cameraIntent)
        } catch (e: ActivityNotFoundException) {
          Log.e("Camera", "No camera app found to handle IMAGE_CAPTURE intent", e)
          Toast.makeText(context, "No camera app found", Toast.LENGTH_SHORT).show()
        }
      },
      onMediaPickerClick = {
        onDismissImageSourceSheet()
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
      },
      onGalleryClick = {
        onDismissImageSourceSheet()
        val galleryIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        pickFromGallery.launch(galleryIntent)
      },
      onFilePickerClick = {
        onDismissImageSourceSheet()
        pickFromFiles.launch(arrayOf("image/*"))
      },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageSourceBottomSheet(
  onDismiss: () -> Unit,
  showFilePickerOption: Boolean,
  onCameraClick: () -> Unit,
  onMediaPickerClick: () -> Unit,
  onGalleryClick: () -> Unit,
  onFilePickerClick: () -> Unit,
) {
  val bottomSheetState = rememberModalBottomSheetState()

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = bottomSheetState,
    dragHandle = { BottomSheetDefaults.DragHandle() },
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(16.dp)
          .padding(bottom = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
      ) {
        // Camera (always present)
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.clickable { onCameraClick() },
        ) {
          Icon(
            painter = painterResource(id = R.drawable.camera),
            contentDescription = "Camera",
            modifier =
              Modifier
                .size(48.dp)
                .padding(bottom = 8.dp),
            tint = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = "Camera",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
          )
        }

        // Conditional: Photos (Android 13+) or Gallery (older versions)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          // Modern Photos picker for Android 13+
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable { onMediaPickerClick() },
          ) {
            Icon(
              painter = painterResource(id = R.drawable.gallery),
              contentDescription = "Photos",
              modifier =
                Modifier
                  .size(48.dp)
                  .padding(bottom = 8.dp),
              tint = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = "Photos",
              style = MaterialTheme.typography.bodyMedium,
              textAlign = TextAlign.Center,
            )
          }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
          // Traditional Gallery for older Android versions
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable { onGalleryClick() },
          ) {
            Icon(
              painter = painterResource(id = R.drawable.gallery),
              contentDescription = "Gallery",
              modifier =
                Modifier
                  .size(48.dp)
                  .padding(bottom = 8.dp),
              tint = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = "Gallery",
              style = MaterialTheme.typography.bodyMedium,
              textAlign = TextAlign.Center,
            )
          }
        }

        if (showFilePickerOption) {
          // File picker
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable { onFilePickerClick() },
          ) {
            Icon(
              painter = painterResource(id = R.drawable.folder),
              contentDescription = "Files",
              modifier =
                Modifier
                  .size(48.dp)
                  .padding(bottom = 8.dp),
              tint = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = "Files",
              style = MaterialTheme.typography.bodyMedium,
              textAlign = TextAlign.Center,
            )
          }
        }
      }
    }
  }
}
