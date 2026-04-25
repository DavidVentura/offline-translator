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

package dev.davidv.translator.ui.screens

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.davidv.translator.AppSettings
import dev.davidv.translator.BackgroundMode
import dev.davidv.translator.DownloadService
import dev.davidv.translator.DownloadState
import dev.davidv.translator.Language
import dev.davidv.translator.LanguageCatalog
import dev.davidv.translator.LanguageMetadataManager
import dev.davidv.translator.PermissionHelper
import dev.davidv.translator.R
import dev.davidv.translator.ReadonlyModalOutputAlignment
import dev.davidv.translator.TapToTranslateNotification
import dev.davidv.translator.displayName
import dev.davidv.translator.ui.theme.TranslatorTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(
  label: String,
  selectedLanguage: Language?,
  availableLanguages: List<Language>,
  fallbackLanguage: Language?,
  onLanguageSelected: (Language) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }

  Text(
    text = label,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurface,
  )

  ExposedDropdownMenuBox(
    expanded = expanded,
    onExpandedChange = { expanded = it },
    modifier = Modifier.fillMaxWidth(),
  ) {
    OutlinedTextField(
      value = selectedLanguage?.displayName ?: fallbackLanguage?.displayName ?: "No languages available",
      onValueChange = {},
      readOnly = true,
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier =
        Modifier
          .menuAnchor()
          .fillMaxWidth(),
      colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
    )
    ExposedDropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
    ) {
      availableLanguages.forEach { language ->
        DropdownMenuItem(
          text = { Text(language.displayName) },
          onClick = {
            onLanguageSelected(language)
            expanded = false
          },
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  settings: AppSettings,
  languageMetadataManager: dev.davidv.translator.LanguageMetadataManager,
  availableLanguages: List<Language>,
  catalog: LanguageCatalog?,
  adblockDownloadState: DownloadState,
  adblockInstalled: Boolean,
  onSettingsChange: (AppSettings) -> Unit,
  onManageLanguages: () -> Unit,
  onDeleteAdblockSupport: () -> Unit,
) {
  val context = LocalContext.current
  var showPermissionDialog by remember { mutableStateOf(false) }
  var assistantRoleHeld by remember { mutableStateOf(isAssistantRoleHeld(context)) }

  val permissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
      val allGranted = permissions.values.all { it }
      if (allGranted) {
        onSettingsChange(settings.copy(useExternalStorage = true))
      } else {
        onSettingsChange(settings.copy(useExternalStorage = false))
      }
    }

  val manageStorageLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.StartActivityForResult(),
    ) { _ ->
      val gotPerms = PermissionHelper.hasExternalStoragePermission(context)
      onSettingsChange(settings.copy(useExternalStorage = gotPerms))
    }

  val assistantRoleLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.StartActivityForResult(),
    ) { _ ->
      assistantRoleHeld = isAssistantRoleHeld(context)
    }

  val notificationPermissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
      if (granted) {
        onSettingsChange(settings.copy(tapToTranslateEnabled = true))
        TapToTranslateNotification.show(context)
      }
    }
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Settings") },
      )
    },
  ) { paddingValues ->
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(paddingValues)
          .padding(16.dp)
          .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // Languages Section
      Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
          CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
          ),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Text(
            text = "Languages",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
          )

          // Manage Languages Button
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "Language Packs",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )

            TextButton(
              onClick = onManageLanguages,
            ) {
              Text("Manage")
            }
          }
        }
      }

      // General Settings Section
      Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
          CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
          ),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = "General",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
          )

          LanguageDropdown(
            label = "Default 'from' language",
            selectedLanguage = settings.defaultSourceLanguageCode?.let { catalog?.languageByCode(it) },
            availableLanguages = availableLanguages,
            fallbackLanguage = availableLanguages.firstOrNull { it.code != settings.defaultTargetLanguageCode },
            onLanguageSelected = { language ->
              onSettingsChange(settings.copy(defaultSourceLanguageCode = language.code))
            },
          )

          LanguageDropdown(
            label = "Default 'to' language",
            selectedLanguage = catalog?.languageByCode(settings.defaultTargetLanguageCode),
            availableLanguages = availableLanguages,
            fallbackLanguage = null,
            onLanguageSelected = { language ->
              onSettingsChange(settings.copy(defaultTargetLanguageCode = language.code))
            },
          )

          Text(
            text = "Font Size",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )

          var showExampleText by remember { mutableStateOf(false) }

          Slider(
            value = settings.fontFactor,
            onValueChange = { value ->
              onSettingsChange(settings.copy(fontFactor = value))
              showExampleText = true
            },
            valueRange = 1.0f..3.0f,
            steps = 3,
            modifier = Modifier.fillMaxWidth(),
          )

          LaunchedEffect(settings.fontFactor) {
            if (showExampleText) {
              delay(1500)
              showExampleText = false
            }
          }

          if (showExampleText) {
            Text(
              text = "This is some example text",
              style =
                MaterialTheme.typography.bodyLarge.copy(
                  fontSize = (MaterialTheme.typography.bodyLarge.fontSize * settings.fontFactor),
                  lineHeight = (MaterialTheme.typography.bodyLarge.lineHeight * settings.fontFactor),
                ),
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Tap to Translate notification",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = "Persistent notification that opens the popup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }

            Switch(
              checked = settings.tapToTranslateEnabled,
              onCheckedChange = { checked ->
                if (checked) {
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    androidx.core.content.ContextCompat.checkSelfPermission(
                      context,
                      android.Manifest.permission.POST_NOTIFICATIONS,
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                  ) {
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                  } else {
                    onSettingsChange(settings.copy(tapToTranslateEnabled = true))
                    TapToTranslateNotification.show(context)
                  }
                } else {
                  onSettingsChange(settings.copy(tapToTranslateEnabled = false))
                  TapToTranslateNotification.hide(context)
                }
              },
            )
          }
        }
      }

      // Web Translator Section
      Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
          CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
          ),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = "Web Translator",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
          )

          WebTranslatorAssetRow(
            label = "Adblock support",
            secondaryLabel = catalog?.supportSizeBytesByKind("adblock")?.let(::formatSize),
            installed = adblockInstalled,
            downloadState = adblockDownloadState,
            onDownload = { DownloadService.startAdblockDownload(context) },
            onDelete = onDeleteAdblockSupport,
            onCancel = { DownloadService.cancelAdblockDownload(context) },
          )
        }
      }

      Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
          CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
          ),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = "Popup settings",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
          )

          Text(
            text = "Popup shows up when selecting 'Translate' from the long-press menu",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Hide input",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }

            Switch(
              checked = settings.onlyShowOutputOnReadonlyModal,
              onCheckedChange = { checked ->
                onSettingsChange(settings.copy(onlyShowOutputOnReadonlyModal = checked))
              },
            )
          }

          var readonlyModalAlignmentExpanded by remember { mutableStateOf(false) }

          Text(
            text = "Popup position",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )

          ExposedDropdownMenuBox(
            expanded = readonlyModalAlignmentExpanded,
            onExpandedChange = { readonlyModalAlignmentExpanded = it },
            modifier = Modifier.fillMaxWidth(),
          ) {
            OutlinedTextField(
              value = settings.readonlyModalOutputAlignment.displayName,
              onValueChange = {},
              readOnly = true,
              trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = readonlyModalAlignmentExpanded)
              },
              modifier =
                Modifier
                  .menuAnchor()
                  .fillMaxWidth(),
              colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            )
            ExposedDropdownMenu(
              expanded = readonlyModalAlignmentExpanded,
              onDismissRequest = { readonlyModalAlignmentExpanded = false },
            ) {
              ReadonlyModalOutputAlignment.entries.forEach { alignment ->
                DropdownMenuItem(
                  text = { Text(alignment.displayName) },
                  onClick = {
                    onSettingsChange(settings.copy(readonlyModalOutputAlignment = alignment))
                    readonlyModalAlignmentExpanded = false
                  },
                )
              }
            }
          }

          Text(
            text = "Popup size: ${(settings.readonlyModalCompactHeightFactor * 100).toInt()}%",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )

          Slider(
            value = settings.readonlyModalCompactHeightFactor,
            onValueChange = { value ->
              onSettingsChange(
                settings.copy(readonlyModalCompactHeightFactor = (value * 20f).roundToInt() / 20f),
              )
            },
            valueRange = 0.2f..0.8f,
            steps = 11,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }

      Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
          CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
          ),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = "Screen translation",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
          )

          Text(
            text =
              "Translate text directly on top of other apps.\n" +
                "Tech preview, expect bugs.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Device Assistant",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text =
                  "Requires assistant gesture.\n" +
                    "Higher quality.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }

            TextButton(
              onClick = {
                if (shouldLaunchAssistantRoleRequest(context)) {
                  val roleManager = context.getSystemService(RoleManager::class.java) ?: return@TextButton
                  assistantRoleLauncher.launch(
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT),
                  )
                } else {
                  openAssistantSettings(context)
                }
              },
            ) {
              Text(if (assistantRoleHeld) "Manage" else "Request")
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Floating shortcut",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text =
                  "Requires accessibility permissions\n" +
                    "Quality depends on target app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }

            TextButton(
              onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
              },
            ) {
              Text("Manage")
            }
          }
        }
      }

      // OCR Settings Section - Only show if OCR is not disabled
      if (!settings.disableOcr) {
        Card(
          modifier = Modifier.fillMaxWidth(),
          colors =
            CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            ),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              text = "OCR",
              style = MaterialTheme.typography.headlineSmall,
              color = MaterialTheme.colorScheme.primary,
            )

            // Background Mode
            var backgroundModeExpanded by remember { mutableStateOf(false) }

            Text(
              text = "Background Mode",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )

            ExposedDropdownMenuBox(
              expanded = backgroundModeExpanded,
              onExpandedChange = { backgroundModeExpanded = it },
              modifier = Modifier.fillMaxWidth(),
            ) {
              OutlinedTextField(
                value = settings.backgroundMode.displayName,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = backgroundModeExpanded) },
                modifier =
                  Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
              )
              ExposedDropdownMenu(
                expanded = backgroundModeExpanded,
                onDismissRequest = { backgroundModeExpanded = false },
              ) {
                BackgroundMode.entries.forEach { mode ->
                  DropdownMenuItem(
                    text = { Text(mode.displayName) },
                    onClick = {
                      onSettingsChange(settings.copy(backgroundMode = mode))
                      backgroundModeExpanded = false
                    },
                  )
                }
              }
            }

            // Min Confidence Slider
            Text(
              text = "Min Confidence: ${settings.minConfidence}%",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )

            Slider(
              value = settings.minConfidence.toFloat(),
              onValueChange = { value ->
                onSettingsChange(settings.copy(minConfidence = value.toInt()))
              },
              valueRange = 50f..100f,
              steps = 9,
              modifier = Modifier.fillMaxWidth(),
            )

            // Max Image Size Slider
            Text(
              text = "Max Image Size: ${settings.maxImageSize}px",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )

            Slider(
              value = settings.maxImageSize.toFloat(),
              onValueChange = { value ->
                onSettingsChange(settings.copy(maxImageSize = value.toInt()))
              },
              valueRange = 1500f..4000f,
              steps = 24,
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
      }

      // Advanced Settings Section
      var advancedExpanded by remember { mutableStateOf(false) }
      Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
          CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
          ),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          // Clickable header
          Row(
            modifier =
              Modifier
                .fillMaxWidth()
                .clickable { advancedExpanded = !advancedExpanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "Advanced Settings",
              style = MaterialTheme.typography.headlineSmall,
              color = MaterialTheme.colorScheme.primary,
            )

            Icon(
              painter =
                painterResource(
                  id = if (advancedExpanded) R.drawable.expandless else R.drawable.expandmore,
                ),
              contentDescription = if (advancedExpanded) "Collapse" else "Expand",
              tint = MaterialTheme.colorScheme.primary,
            )
          }

          // Expandable content
          if (advancedExpanded) {
            // Catalog Index URL
            Text(
              text = "Catalog Index URL",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )

            OutlinedTextField(
              value = settings.catalogIndexUrl,
              onValueChange = {
                onSettingsChange(settings.copy(catalogIndexUrl = it))
              },
              modifier = Modifier.fillMaxWidth(),
              singleLine = true,
            )

            // External Storage Toggle
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = "Use external storage",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )

              Switch(
                checked = settings.useExternalStorage,
                onCheckedChange = { checked ->
                  if (checked) {
                    if (PermissionHelper.hasExternalStoragePermission(context)) {
                      onSettingsChange(settings.copy(useExternalStorage = true))
                    } else if (PermissionHelper.needsSpecialPermissionIntent()) {
                      // Android 11+ - Show dialog first, then launch Settings
                      showPermissionDialog = true
                    } else {
                      // Android 10 and below - Request runtime permissions
                      val permissions = PermissionHelper.getExternalStoragePermissions()
                      if (permissions.isNotEmpty()) {
                        permissionLauncher.launch(permissions)
                      } else {
                        onSettingsChange(settings.copy(useExternalStorage = true))
                      }
                    }
                  } else {
                    onSettingsChange(settings.copy(useExternalStorage = false))
                  }
                },
              )
            }

            // Disable OCR Toggle
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = "Disable OCR",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )

              Switch(
                checked = settings.disableOcr,
                onCheckedChange = { checked ->
                  onSettingsChange(settings.copy(disableOcr = checked))
                },
              )
            }

            // Show OCR Detection Toggle
            if (!settings.disableOcr) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = "Show OCR detection",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )

                Switch(
                  checked = settings.showOCRDetection,
                  onCheckedChange = { checked ->
                    onSettingsChange(settings.copy(showOCRDetection = checked))
                  },
                )
              }

              // Show Gallery in Image Picker Toggle
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = "Show file picker for OCR",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )

                Switch(
                  checked = settings.showFilePickerInImagePicker,
                  onCheckedChange = { checked ->
                    onSettingsChange(settings.copy(showFilePickerInImagePicker = checked))
                  },
                )
              }
            }

            // Disable CLD Toggle
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = "Disable automatic language detection",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )

              Switch(
                checked = settings.disableCLD,
                onCheckedChange = { checked ->
                  onSettingsChange(settings.copy(disableCLD = checked))
                },
              )
            }

            // Show Transliteration for Output Toggle
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = "Show transliteration for output",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )

              Switch(
                checked = settings.enableOutputTransliteration,
                onCheckedChange = { checked ->
                  onSettingsChange(settings.copy(enableOutputTransliteration = checked))
                },
              )
            }

            // Show Transliteration on Input Toggle
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = "Show transliteration for input",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )

              Switch(
                checked = settings.showTransliterationOnInput,
                onCheckedChange = { checked ->
                  onSettingsChange(settings.copy(showTransliterationOnInput = checked))
                },
              )
            }

            // Add Spaces for Japanese Transliteration Toggle
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = "Add spaces for Japanese transliteration",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )

              Switch(
                checked = settings.addSpacesForJapaneseTransliteration,
                onCheckedChange = { checked ->
                  onSettingsChange(settings.copy(addSpacesForJapaneseTransliteration = checked))
                },
              )
            }
          }
        }
      }
    }
  }

  // Permission explanation dialog
  if (showPermissionDialog) {
    AlertDialog(
      onDismissRequest = {
        showPermissionDialog = false
      },
      title = { Text("External Storage Permission") },
      text = {
        Text(
          "To store translation files in your Documents folder, " +
            "this app needs access to manage all files.\nYou'll be taken to Settings where you can grant " +
            "'Allow access to manage all files' permission.",
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            showPermissionDialog = false
            val intent = PermissionHelper.createManageStorageIntent(context)
            manageStorageLauncher.launch(intent)
          },
        ) {
          Text("OK")
        }
      },
      dismissButton = {
        TextButton(
          onClick = {
            showPermissionDialog = false
          },
        ) {
          Text("Cancel")
        }
      },
    )
  }
}

@Composable
private fun WebTranslatorAssetRow(
  label: String,
  secondaryLabel: String?,
  installed: Boolean,
  downloadState: DownloadState,
  onDownload: () -> Unit,
  onDelete: () -> Unit,
  onCancel: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      secondaryLabel?.let { size ->
        Text(
          text = size,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    SupportActionButton(
      downloadState = downloadState,
      installed = installed,
      onDownload = onDownload,
      onDelete = onDelete,
      onCancel = onCancel,
    )
  }
}

@Composable
private fun SupportActionButton(
  downloadState: DownloadState,
  installed: Boolean,
  onDownload: () -> Unit,
  onDelete: () -> Unit,
  onCancel: () -> Unit,
) {
  if (downloadState.isDownloading) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier.size(40.dp),
    ) {
      val targetProgress =
        if (downloadState.totalSize > 0) {
          downloadState.downloaded.toFloat() / downloadState.totalSize.toFloat()
        } else {
          0f
        }
      val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 300),
        label = "adblock-progress",
      )
      CircularProgressIndicator(
        progress = { animatedProgress },
        modifier = Modifier.size(32.dp),
      )
      IconButton(
        onClick = onCancel,
        modifier = Modifier.size(32.dp),
      ) {
        Icon(
          painter = painterResource(id = R.drawable.cancel),
          contentDescription = "Cancel Download",
        )
      }
    }
    return
  }

  IconButton(
    onClick = if (installed) onDelete else onDownload,
    modifier = Modifier.size(40.dp),
  ) {
    Icon(
      painter =
        painterResource(
          id =
            when {
              installed -> R.drawable.delete
              downloadState.isCancelled || downloadState.error != null -> R.drawable.refresh
              else -> R.drawable.add
            },
        ),
      contentDescription =
        when {
          installed -> "Delete"
          downloadState.isCancelled || downloadState.error != null -> "Retry Download"
          else -> "Download"
        },
    )
  }
}

private fun formatSize(sizeBytes: Long): String {
  val units = listOf("B", "KB", "MB", "GB")
  var size = sizeBytes.toDouble()
  var unitIndex = 0
  while (size >= 1024 && unitIndex < units.lastIndex) {
    size /= 1024
    unitIndex++
  }
  return if (unitIndex == 0) {
    "${size.toLong()} ${units[unitIndex]}"
  } else {
    "%.1f %s".format(size, units[unitIndex])
  }
}

private fun isAssistantRoleHeld(context: android.content.Context): Boolean {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
  val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
  if (!roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) return false
  return roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
}

private fun shouldLaunchAssistantRoleRequest(context: Context): Boolean {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
  if (getConfiguredAssistant(context).isNullOrBlank()) return false
  val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
  return roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
    !roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
}

private fun getConfiguredAssistant(context: Context): String? =
  Settings.Secure
    .getString(context.contentResolver, ASSISTANT_SETTING)
    ?.takeIf { it.isNotBlank() }

private fun openAssistantSettings(context: Context) {
  val settingsIntents =
    listOf(
      Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
      Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
      Intent(Settings.ACTION_SETTINGS),
    )

  settingsIntents
    .firstOrNull { intent ->
      intent.resolveActivity(context.packageManager) != null
    }?.let { intent ->
      context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private const val ASSISTANT_SETTING = "assistant"

private fun previewLanguage(
  code: String,
  name: String,
) = Language(
  code = code,
  displayName = name,
  shortDisplayName = name,
  tessName = code,
  script = "Latn",
  dictionaryCode = code,
  tessdataSizeBytes = 0,
)

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
  val context = LocalContext.current
  val previewLangs =
    listOf(
      previewLanguage("en", "English"),
      previewLanguage("es", "Spanish"),
      previewLanguage("fr", "French"),
    )
  TranslatorTheme {
    SettingsScreen(
      settings = AppSettings(),
      languageMetadataManager = LanguageMetadataManager(context, kotlinx.coroutines.flow.MutableStateFlow(emptyList())),
      availableLanguages = previewLangs,
      catalog = null,
      adblockDownloadState = DownloadState(),
      adblockInstalled = false,
      onSettingsChange = {},
      onManageLanguages = {},
      onDeleteAdblockSupport = {},
    )
  }
}

@Preview(
  showBackground = true,
  uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun SettingsScreenDarkPreview() {
  val context = LocalContext.current
  val previewLangs =
    listOf(
      previewLanguage("en", "English"),
      previewLanguage("es", "Spanish"),
      previewLanguage("fr", "French"),
    )
  TranslatorTheme {
    SettingsScreen(
      settings = AppSettings(fontFactor = 3.0f),
      languageMetadataManager = LanguageMetadataManager(context, kotlinx.coroutines.flow.MutableStateFlow(emptyList())),
      availableLanguages = previewLangs,
      catalog = null,
      adblockDownloadState = DownloadState(),
      adblockInstalled = true,
      onSettingsChange = {},
      onManageLanguages = {},
      onDeleteAdblockSupport = {},
    )
  }
}
