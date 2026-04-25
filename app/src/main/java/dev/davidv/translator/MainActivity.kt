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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import dev.davidv.translator.ui.TranslatorViewModel
import dev.davidv.translator.ui.TranslatorViewModelFactory
import dev.davidv.translator.ui.screens.TranslatorApp
import dev.davidv.translator.ui.theme.TranslatorTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {
  private var textToTranslate: String = ""
  private var launchMode: LaunchMode = LaunchMode.Normal
  private var sharedImageUri: Uri? = null
  private lateinit var viewModel: TranslatorViewModel
  private var downloadService: DownloadService? = null
  private lateinit var serviceConnection: ServiceConnection
  private val _downloadServiceState = MutableStateFlow<DownloadService?>(null)
  private val downloadServiceState: StateFlow<DownloadService?> = _downloadServiceState.asStateFlow()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    handleIntent(intent)
    if (isFinishing) return

    val app = application as TranslatorApplication

    viewModel =
      ViewModelProvider(
        this,
        TranslatorViewModelFactory(
          translationCoordinator = app.translationCoordinator,
          settingsManager = app.settingsManager,
          filePathManager = app.filePathManager,
          languageMetadataManager = app.languageMetadataManager,
          initialText = textToTranslate,
          initialLaunchMode = launchMode,
        ),
      )[TranslatorViewModel::class.java]

    // Set shared image if present
    sharedImageUri?.let { viewModel.setSharedImageUri(it) }

    setContent {
      TranslatorTheme {
        TranslatorApp(
          viewModel = viewModel,
          downloadServiceState = downloadServiceState,
        )
      }
    }

    serviceConnection =
      object : ServiceConnection {
        override fun onServiceConnected(
          name: ComponentName?,
          service: IBinder?,
        ) {
          Log.d("ServiceConnection", "download service done")
          val binder = service as DownloadService.DownloadBinder
          downloadService = binder.getService()
          _downloadServiceState.value = downloadService
        }

        override fun onServiceDisconnected(name: ComponentName?) {
          downloadService = null
          _downloadServiceState.value = null
        }
      }

    val serviceIntent = Intent(this, DownloadService::class.java)
    bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
  }

  override fun onDestroy() {
    super.onDestroy()
    if (::serviceConnection.isInitialized) {
      unbindService(serviceConnection)
    }
    Log.i("MainActivity", "cleaning up")
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleIntent(intent)
  }

  private fun handleIntent(intent: Intent?) {
    Log.d("MainActivity", "Got intent $intent")
    when (intent?.action) {
      Intent.ACTION_SEND -> {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val imageUri =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
          } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
          }

        if (text != null) {
          val url = firstUrlInText(text)
          if (url != null) {
            val browserIntent =
              Intent(this, dev.davidv.translator.browser.BrowserActivity::class.java).apply {
                putExtra(dev.davidv.translator.browser.BrowserActivity.EXTRA_URL, url)
              }
            startActivity(browserIntent)
            finish()
            return
          }
          textToTranslate = text
        } else if (imageUri != null) {
          sharedImageUri = imageUri
          textToTranslate = ""
        }
      }
    }
  }

  private fun firstUrlInText(text: String): String? {
    for (token in text.trim().split(Regex("\\s+"))) {
      if (token.startsWith("http://") || token.startsWith("https://")) return token
    }
    return null
  }
}
