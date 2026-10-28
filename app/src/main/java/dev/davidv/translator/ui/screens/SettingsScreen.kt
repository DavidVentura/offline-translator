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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.davidv.translator.AppSettings
import dev.davidv.translator.BackgroundMode
import dev.davidv.translator.CatalogRepository
import dev.davidv.translator.DownloadStatus
import dev.davidv.translator.FilePathManager
import dev.davidv.translator.HttpServerBindMode
import dev.davidv.translator.Language
import dev.davidv.translator.LanguageCatalog
import dev.davidv.translator.PermissionHelper
import dev.davidv.translator.R
import dev.davidv.translator.ReadonlyModalOutputAlignment
import dev.davidv.translator.ReadonlyPopupSize
import dev.davidv.translator.Script
import dev.davidv.translator.accessibilityOverlay.isAccessibilityServiceOffered
import dev.davidv.translator.accessibilityOverlay.setAccessibilityServiceOffered
import dev.davidv.translator.inFlightProgress
import dev.davidv.translator.labelRes
import dev.davidv.translator.languageNameComparator
import dev.davidv.translator.localizedName
import dev.davidv.translator.singleScript
import dev.davidv.translator.ui.components.AppCard
import dev.davidv.translator.ui.theme.TranslatorTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(
  label: String,
  selectedLanguage: Language?,
  availableLanguages: List<Language>,
  onLanguageSelected: (Language) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  val languagesByDisplayName =
    remember(availableLanguages) {
      availableLanguages.sortedWith(compareBy(languageNameComparator()) { it.localizedName() })
    }

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
      value = selectedLanguage?.localizedName() ?: stringResource(R.string.settings_no_languages_available),
      onValueChange = {},
      readOnly = true,
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier =
        Modifier
          .menuAnchor(MenuAnchorType.PrimaryNotEditable)
          .fillMaxWidth(),
      colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
    )
    ExposedDropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
    ) {
      languagesByDisplayName.forEach { language ->
        DropdownMenuItem(
          text = { Text(language.localizedName()) },
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
private fun HttpServerCard(
  settings: AppSettings,
  onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
) {
  AppCard(
    modifier = Modifier.fillMaxWidth().testTag("export-section:HttpServer"),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = stringResource(R.string.settings_http_server_title),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
      )

      // Commit the port after a pause so per-keystroke edits don't rebind the
      // running server (and briefly try to bind partial numbers like "5").
      var portText by remember(settings.httpServerPort) { mutableStateOf(settings.httpServerPort.toString()) }
      LaunchedEffect(portText) {
        delay(500)
        portText.toIntOrNull()?.takeIf { it in 1..65535 }?.let { port ->
          if (port != settings.httpServerPort) onSettingsChange { it.copy(httpServerPort = port) }
        }
      }

      Text(
        text = stringResource(R.string.settings_http_server_port),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      OutlinedTextField(
        value = portText,
        onValueChange = { input -> portText = input.filter { it.isDigit() }.take(5) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      )

      Text(
        text = stringResource(R.string.settings_http_server_bind),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      var bindExpanded by remember { mutableStateOf(false) }
      ExposedDropdownMenuBox(
        expanded = bindExpanded,
        onExpandedChange = { bindExpanded = it },
        modifier = Modifier.fillMaxWidth(),
      ) {
        OutlinedTextField(
          value = stringResource(settings.httpServerBindMode.labelRes),
          onValueChange = {},
          readOnly = true,
          trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = bindExpanded) },
          modifier =
            Modifier
              .menuAnchor(MenuAnchorType.PrimaryNotEditable)
              .fillMaxWidth(),
          colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
        )
        ExposedDropdownMenu(
          expanded = bindExpanded,
          onDismissRequest = { bindExpanded = false },
        ) {
          HttpServerBindMode.entries.forEach { mode ->
            DropdownMenuItem(
              text = { Text(stringResource(mode.labelRes)) },
              onClick = {
                onSettingsChange { it.copy(httpServerBindMode = mode) }
                bindExpanded = false
              },
            )
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  settings: AppSettings,
  sourceLanguages: List<Language>,
  targetLanguages: List<Language>,
  catalog: LanguageCatalog,
  adblockDownload: DownloadStatus?,
  adblockInstalled: Boolean,
  onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
  onDefaultTargetLanguageSelected: (Language) -> Unit,
  onManageLanguages: () -> Unit,
  onDownloadAdblockSupport: () -> Unit,
  onCancelAdblockDownload: () -> Unit,
  onDeleteAdblockSupport: () -> Unit,
  onHowToUse: () -> Unit,
) {
  val context = LocalContext.current
  var showPermissionDialog by remember { mutableStateOf(false) }
  var accessibilityServiceOffered by remember { mutableStateOf(isAccessibilityServiceOffered(context)) }

  val permissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
      val allGranted = permissions.values.all { it }
      if (allGranted) {
        onSettingsChange { it.copy(useExternalStorage = true) }
      } else {
        onSettingsChange { it.copy(useExternalStorage = false) }
      }
    }

  val manageStorageLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.StartActivityForResult(),
    ) { _ ->
      val gotPerms = PermissionHelper.hasExternalStoragePermission(context)
      onSettingsChange { it.copy(useExternalStorage = gotPerms) }
    }

  val assistantRoleLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.StartActivityForResult(),
    ) { _ -> }

  val notificationPermissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
      if (granted) {
        onSettingsChange { it.copy(tapToTranslateEnabled = true) }
      }
    }
  Scaffold(
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.settings_title)) },
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
      AppCard(
        modifier = Modifier.fillMaxWidth().testTag("export-section:Languages"),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Text(
            text = stringResource(R.string.settings_languages),
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
              text = stringResource(R.string.settings_language_packs),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.weight(1f).padding(end = 8.dp),
            )

            TextButton(
              onClick = onManageLanguages,
            ) {
              Text(stringResource(R.string.common_manage))
            }
          }
        }
      }

      // General Settings Section
      AppCard(
        modifier = Modifier.fillMaxWidth().testTag("export-section:General"),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = stringResource(R.string.settings_general),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
          )

          LanguageDropdown(
            label = stringResource(R.string.settings_default_from),
            selectedLanguage = settings.defaultSourceLanguageCode?.let { catalog.languageByCode(it) },
            availableLanguages = sourceLanguages,
            onLanguageSelected = { language ->
              onSettingsChange { it.copy(defaultSourceLanguageCode = language.code) }
            },
          )

          LanguageDropdown(
            label = stringResource(R.string.settings_default_to),
            selectedLanguage = catalog.languageByCode(settings.defaultTargetLanguageCode),
            availableLanguages = targetLanguages,
            onLanguageSelected = { language ->
              onDefaultTargetLanguageSelected(language)
            },
          )

          Text(
            text = stringResource(R.string.settings_font_size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )

          var showExampleText by remember { mutableStateOf(false) }

          Slider(
            value = settings.fontFactor,
            onValueChange = { value ->
              onSettingsChange { it.copy(fontFactor = value) }
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
              text = stringResource(R.string.settings_font_sample),
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
                text = stringResource(R.string.settings_tap_notification),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = stringResource(R.string.settings_tap_notification_desc),
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
                    onSettingsChange { it.copy(tapToTranslateEnabled = true) }
                  }
                } else {
                  onSettingsChange { it.copy(tapToTranslateEnabled = false) }
                }
              },
            )
          }
        }
      }

      // Web Translator Section
      AppCard(
        modifier = Modifier.fillMaxWidth().testTag("export-section:Web Translator"),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = stringResource(R.string.settings_web_translator),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
          )

          WebTranslatorAssetRow(
            label = stringResource(R.string.settings_adblock_support),
            secondaryLabel = formatSize(catalog.supportSizeBytesByKind("adblock")),
            installed = adblockInstalled,
            downloadStatus = adblockDownload,
            onDownload = onDownloadAdblockSupport,
            onDelete = onDeleteAdblockSupport,
            onCancel = onCancelAdblockDownload,
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = stringResource(R.string.settings_clear_browsing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = stringResource(R.string.settings_clear_browsing_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(
              checked = settings.clearWebTranslatorDataOnClose,
              onCheckedChange = { enabled ->
                onSettingsChange { it.copy(clearWebTranslatorDataOnClose = enabled) }
              },
            )
          }
        }
      }

      // Text to Speech Section
      AppCard(
        modifier = Modifier.fillMaxWidth().testTag("export-section:Text to speech"),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = stringResource(R.string.settings_tts_section),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = stringResource(R.string.settings_tts_read_urls_hashtags),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Switch(
              checked = settings.ttsReadUrlsAndHashtags,
              onCheckedChange = { enabled ->
                onSettingsChange { it.copy(ttsReadUrlsAndHashtags = enabled) }
              },
            )
          }
        }
      }

      AppCard(
        modifier = Modifier.fillMaxWidth().testTag("export-section:Popup"),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = stringResource(R.string.settings_popup_settings),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
          )

          Text(
            text = stringResource(R.string.settings_popup_desc),
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
                text = stringResource(R.string.settings_hide_input),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }

            Switch(
              checked = settings.onlyShowOutputOnReadonlyModal,
              onCheckedChange = { checked ->
                onSettingsChange { it.copy(onlyShowOutputOnReadonlyModal = checked) }
              },
            )
          }

          var readonlyModalAlignmentExpanded by remember { mutableStateOf(false) }

          Text(
            text = stringResource(R.string.settings_popup_position),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )

          ExposedDropdownMenuBox(
            expanded = readonlyModalAlignmentExpanded,
            onExpandedChange = { readonlyModalAlignmentExpanded = it },
            modifier = Modifier.fillMaxWidth(),
          ) {
            OutlinedTextField(
              value = stringResource(settings.readonlyModalOutputAlignment.labelRes),
              onValueChange = {},
              readOnly = true,
              trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = readonlyModalAlignmentExpanded)
              },
              modifier =
                Modifier
                  .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                  .fillMaxWidth()
                  .testTag("export-options:settings_popup_position"),
              colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            )
            ExposedDropdownMenu(
              expanded = readonlyModalAlignmentExpanded,
              onDismissRequest = { readonlyModalAlignmentExpanded = false },
            ) {
              ReadonlyModalOutputAlignment.entries.forEach { alignment ->
                DropdownMenuItem(
                  text = { Text(stringResource(alignment.labelRes)) },
                  onClick = {
                    onSettingsChange { it.copy(readonlyModalOutputAlignment = alignment) }
                    readonlyModalAlignmentExpanded = false
                  },
                )
              }
            }
          }

          Text(
            text =
              when (val size = settings.readonlyPopupSize) {
                is ReadonlyPopupSize.Fraction -> stringResource(R.string.settings_popup_size, (size.heightFactor * 100).roundToInt())
                ReadonlyPopupSize.Expanded -> stringResource(R.string.settings_popup_size_expanded)
              },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )

          Slider(
            value = settings.readonlyPopupSize.sliderPosition,
            onValueChange = { value ->
              val size = checkNotNull(ReadonlyPopupSize.fromSliderPosition(value)) { "slider produced $value" }
              onSettingsChange { it.copy(readonlyPopupSize = size) }
            },
            valueRange = ReadonlyPopupSize.SLIDER_RANGE,
            steps = ReadonlyPopupSize.SLIDER_STEPS,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }

      AppCard(
        modifier = Modifier.fillMaxWidth().testTag("export-section:Screen translation"),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = stringResource(R.string.settings_screen_translation),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
          )

          Text(
            text = stringResource(R.string.settings_screen_translation_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && accessibilityServiceOffered) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = stringResource(R.string.settings_floating_button),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                  text = stringResource(R.string.settings_floating_button_desc),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }

              TextButton(
                onClick = {
                  context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
              ) {
                Text(stringResource(R.string.common_manage))
              }
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = stringResource(R.string.settings_shortcut),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = stringResource(R.string.settings_shortcut_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }

            TextButton(
              onClick = {
                val roleRequest = assistantRoleRequest(context)
                if (roleRequest != null) {
                  assistantRoleLauncher.launch(roleRequest)
                } else {
                  openAssistantSettings(context)
                }
              },
            ) {
              Text(stringResource(R.string.common_manage))
            }
          }

          var assistantActionExpanded by remember { mutableStateOf(false) }

          Text(
            text = stringResource(R.string.settings_on_shortcut),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )

          ExposedDropdownMenuBox(
            expanded = assistantActionExpanded,
            onExpandedChange = { assistantActionExpanded = it },
            modifier = Modifier.fillMaxWidth(),
          ) {
            OutlinedTextField(
              value = stringResource(settings.assistantAction.labelRes),
              onValueChange = {},
              readOnly = true,
              trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = assistantActionExpanded)
              },
              modifier =
                Modifier
                  .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                  .fillMaxWidth(),
              colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            )
            ExposedDropdownMenu(
              expanded = assistantActionExpanded,
              onDismissRequest = { assistantActionExpanded = false },
            ) {
              dev.davidv.translator.AssistantAction.entries.forEach { action ->
                DropdownMenuItem(
                  text = { Text(stringResource(action.labelRes)) },
                  onClick = {
                    onSettingsChange { it.copy(assistantAction = action) }
                    assistantActionExpanded = false
                  },
                )
              }
            }
          }
        }
      }

      AppCard(
        modifier = Modifier.fillMaxWidth().testTag("export-section:Images"),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = stringResource(R.string.settings_ocr),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
          )

          // Background Mode
          var backgroundModeExpanded by remember { mutableStateOf(false) }

          Text(
            text = stringResource(R.string.settings_background_mode),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )

          ExposedDropdownMenuBox(
            expanded = backgroundModeExpanded,
            onExpandedChange = { backgroundModeExpanded = it },
            modifier = Modifier.fillMaxWidth(),
          ) {
            OutlinedTextField(
              value = stringResource(settings.backgroundMode.labelRes),
              onValueChange = {},
              readOnly = true,
              trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = backgroundModeExpanded) },
              modifier =
                Modifier
                  .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                  .fillMaxWidth()
                  .testTag("export-options:settings_background_mode"),
              colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            )
            ExposedDropdownMenu(
              expanded = backgroundModeExpanded,
              onDismissRequest = { backgroundModeExpanded = false },
            ) {
              BackgroundMode.entries.forEach { mode ->
                DropdownMenuItem(
                  text = { Text(stringResource(mode.labelRes)) },
                  onClick = {
                    onSettingsChange { it.copy(backgroundMode = mode) }
                    backgroundModeExpanded = false
                  },
                )
              }
            }
          }

          // Min Confidence Slider
          Text(
            text = stringResource(R.string.settings_min_confidence, settings.minConfidence),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )

          Slider(
            value = settings.minConfidence.toFloat(),
            onValueChange = { value ->
              onSettingsChange { it.copy(minConfidence = value.toInt()) }
            },
            valueRange = 70f..100f,
            steps = 5,
            modifier = Modifier.fillMaxWidth(),
          )

          // Max Image Size Slider
          Text(
            text = stringResource(R.string.settings_max_image_size, settings.maxImageSize),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )

          Slider(
            value = settings.maxImageSize.toFloat(),
            onValueChange = { value ->
              onSettingsChange { it.copy(maxImageSize = value.toInt()) }
            },
            valueRange = 1300f..2000f,
            steps = 6,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }

      // Advanced Settings Section
      var advancedExpanded by remember { mutableStateOf(false) }
      AppCard(
        modifier = Modifier.fillMaxWidth().testTag("export-section:Advanced"),
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
              text = stringResource(R.string.settings_advanced),
              style = MaterialTheme.typography.headlineSmall,
              color = MaterialTheme.colorScheme.primary,
            )

            Icon(
              painter =
                painterResource(
                  id = if (advancedExpanded) R.drawable.expandless else R.drawable.expandmore,
                ),
              contentDescription = if (advancedExpanded) stringResource(R.string.a11y_collapse) else stringResource(R.string.a11y_expand),
              tint = MaterialTheme.colorScheme.primary,
            )
          }

          // Expandable content
          if (advancedExpanded) {
            // Catalog Index URL
            Text(
              text = stringResource(R.string.settings_catalog_url),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )

            OutlinedTextField(
              value = settings.catalogIndexUrl,
              onValueChange = { url ->
                onSettingsChange { it.copy(catalogIndexUrl = url) }
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
                text = stringResource(R.string.settings_external_storage),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
              )

              Switch(
                checked = settings.useExternalStorage,
                onCheckedChange = { checked ->
                  if (checked) {
                    if (PermissionHelper.hasExternalStoragePermission(context)) {
                      onSettingsChange { it.copy(useExternalStorage = true) }
                    } else if (PermissionHelper.needsSpecialPermissionIntent()) {
                      // Android 11+ - Show dialog first, then launch Settings
                      showPermissionDialog = true
                    } else {
                      // Android 10 and below - Request runtime permissions
                      val permissions = PermissionHelper.getExternalStoragePermissions()
                      if (permissions.isNotEmpty()) {
                        permissionLauncher.launch(permissions)
                      } else {
                        onSettingsChange { it.copy(useExternalStorage = true) }
                      }
                    }
                  } else {
                    onSettingsChange { it.copy(useExternalStorage = false) }
                  }
                },
              )
            }

            // Disable CLD Toggle
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = stringResource(R.string.settings_disable_autodetect),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
              )

              Switch(
                checked = settings.disableCLD,
                onCheckedChange = { checked ->
                  onSettingsChange { it.copy(disableCLD = checked) }
                },
              )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                  Text(
                    text = stringResource(R.string.settings_offer_accessibility_service),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                  Text(
                    text = stringResource(R.string.settings_offer_accessibility_service_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }

                Switch(
                  checked = accessibilityServiceOffered,
                  onCheckedChange = { checked ->
                    setAccessibilityServiceOffered(context, checked)
                    accessibilityServiceOffered = checked
                  },
                )
              }
            }

            // Experimental screen-translate entry in the input source row
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = stringResource(R.string.settings_experimental_screen_translate),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
              )

              Switch(
                checked = settings.experimentalScreenTranslate,
                onCheckedChange = { checked ->
                  onSettingsChange { it.copy(experimentalScreenTranslate = checked) }
                },
              )
            }

            // Multiple target languages (tabbed output)
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = stringResource(R.string.settings_multi_target),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
              )

              Switch(
                checked = settings.multiTargetEnabled,
                onCheckedChange = { checked ->
                  onSettingsChange { it.copy(multiTargetEnabled = checked) }
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
                text = stringResource(R.string.settings_translit_output),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
              )

              Switch(
                checked = settings.enableOutputTransliteration,
                onCheckedChange = { checked ->
                  onSettingsChange { it.copy(enableOutputTransliteration = checked) }
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
                text = stringResource(R.string.settings_translit_input),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
              )

              Switch(
                checked = settings.showTransliterationOnInput,
                onCheckedChange = { checked ->
                  onSettingsChange { it.copy(showTransliterationOnInput = checked) }
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
                text = stringResource(R.string.settings_translit_japanese_spaces),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
              )

              Switch(
                checked = settings.addSpacesForJapaneseTransliteration,
                onCheckedChange = { checked ->
                  onSettingsChange { it.copy(addSpacesForJapaneseTransliteration = checked) }
                },
              )
            }

            // Translate images embedded in PDFs
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = stringResource(R.string.settings_translate_pdf_images),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
              )
              Switch(
                checked = settings.translatePdfImages,
                onCheckedChange = { checked ->
                  onSettingsChange { it.copy(translatePdfImages = checked) }
                },
              )
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = stringResource(R.string.settings_camera_live_default),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
              )
              Switch(
                checked = settings.liveCameraOverlayEnabled,
                onCheckedChange = { checked ->
                  onSettingsChange { it.copy(liveCameraOverlayEnabled = checked) }
                },
              )
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                  text = stringResource(R.string.settings_register_browser),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                  text = stringResource(R.string.settings_register_browser_desc),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                checked = settings.registerAsBrowser,
                onCheckedChange = { checked ->
                  onSettingsChange { it.copy(registerAsBrowser = checked) }
                },
              )
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                  text = stringResource(R.string.settings_http_server),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                  text = stringResource(R.string.settings_http_server_desc),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                checked = settings.httpServerEnabled,
                onCheckedChange = { checked ->
                  onSettingsChange { it.copy(httpServerEnabled = checked) }
                },
              )
            }
          }
        }
      }

      if (settings.httpServerEnabled) {
        HttpServerCard(settings = settings, onSettingsChange = onSettingsChange)
      }

      AboutCard(
        onHowToUse = onHowToUse,
        onGetHelp = {
          val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
          clipboard.setPrimaryClip(ClipData.newPlainText("App logs", captureAppLogs()))
          Toast.makeText(context, context.getString(R.string.settings_logs_copied), Toast.LENGTH_SHORT).show()
          context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/DavidVentura/offline-translator/issues")),
          )
        },
        onSupport = {
          context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://liberapay.com/DavidV/")),
          )
        },
      )
    }
  }

  // Permission explanation dialog
  if (showPermissionDialog) {
    AlertDialog(
      onDismissRequest = {
        showPermissionDialog = false
      },
      title = { Text(stringResource(R.string.settings_external_storage_perm_title)) },
      text = {
        Text(stringResource(R.string.settings_external_storage_perm_body))
      },
      confirmButton = {
        TextButton(
          onClick = {
            showPermissionDialog = false
            val intent = PermissionHelper.createManageStorageIntent(context)
            manageStorageLauncher.launch(intent)
          },
        ) {
          Text(stringResource(R.string.common_ok))
        }
      },
      dismissButton = {
        TextButton(
          onClick = {
            showPermissionDialog = false
          },
        ) {
          Text(stringResource(R.string.common_cancel))
        }
      },
    )
  }
}

@Composable
private fun AboutCard(
  onHowToUse: () -> Unit,
  onGetHelp: () -> Unit,
  onSupport: () -> Unit,
) {
  val context = LocalContext.current
  val versionName =
    remember {
      runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
      }.getOrNull() ?: "unknown"
    }

  AppCard(
    modifier = Modifier.fillMaxWidth().testTag("export-section:About"),
  ) {
    Column(modifier = Modifier.padding(vertical = 16.dp)) {
      Text(
        text = stringResource(R.string.settings_about),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
      )

      AboutNavRow(label = stringResource(R.string.howto_title), iconRes = R.drawable.question, onClick = onHowToUse)
      AboutNavRow(label = stringResource(R.string.settings_about_get_help), iconRes = R.drawable.info, onClick = onGetHelp)
      AboutNavRow(label = stringResource(R.string.settings_about_support), iconRes = R.drawable.heart, onClick = onSupport)

      Text(
        text = stringResource(R.string.settings_version, versionName),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
      )
    }
  }
}

@Composable
private fun AboutNavRow(
  label: String,
  iconRes: Int,
  onClick: () -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      painter = painterResource(id = iconRes),
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.size(24.dp),
    )
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.padding(start = 16.dp),
    )
  }
}

private fun captureAppLogs(): String =
  runCatching {
    val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "-t", "2000"))
    process.inputStream.bufferedReader().use { it.readText() }
  }.getOrElse { "Failed to read logs: ${it.message}" }

@Composable
private fun WebTranslatorAssetRow(
  label: String,
  secondaryLabel: String?,
  installed: Boolean,
  downloadStatus: DownloadStatus?,
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
      downloadStatus = downloadStatus,
      installed = installed,
      onDownload = onDownload,
      onDelete = onDelete,
      onCancel = onCancel,
    )
  }
}

@Composable
private fun SupportActionButton(
  downloadStatus: DownloadStatus?,
  installed: Boolean,
  onDownload: () -> Unit,
  onDelete: () -> Unit,
  onCancel: () -> Unit,
) {
  val inFlightProgress = downloadStatus.inFlightProgress
  if (inFlightProgress != null) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier.size(40.dp),
    ) {
      val animatedProgress by animateFloatAsState(
        targetValue = inFlightProgress,
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
          contentDescription = stringResource(R.string.a11y_cancel_download),
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
              downloadStatus is DownloadStatus.Failed -> R.drawable.refresh
              else -> R.drawable.add
            },
        ),
      contentDescription =
        when {
          installed -> stringResource(R.string.a11y_delete)
          downloadStatus is DownloadStatus.Failed -> stringResource(R.string.a11y_retry_download)
          else -> stringResource(R.string.a11y_download)
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

private fun assistantRoleRequest(context: Context): Intent? {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
  if (getConfiguredAssistant(context).isNullOrBlank()) return null
  val roleManager = context.getSystemService(RoleManager::class.java) ?: return null
  if (!roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) || roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) return null
  return roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
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
  script = Script.LATIN,
  writingSystem = singleScript(Script.LATIN),
  dictionaryCode = code,
)

@Composable
private fun previewCatalog(context: Context): LanguageCatalog =
  remember {
    CatalogRepository.open(context, FilePathManager(context, MutableStateFlow(AppSettings()))).catalog.value
  }

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
      sourceLanguages = previewLangs,
      targetLanguages = previewLangs,
      catalog = previewCatalog(context),
      adblockDownload = null,
      adblockInstalled = false,
      onSettingsChange = {},
      onDefaultTargetLanguageSelected = {},
      onManageLanguages = {},
      onDownloadAdblockSupport = {},
      onCancelAdblockDownload = {},
      onDeleteAdblockSupport = {},
      onHowToUse = {},
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
      sourceLanguages = previewLangs,
      targetLanguages = previewLangs,
      catalog = previewCatalog(context),
      adblockDownload = null,
      adblockInstalled = true,
      onSettingsChange = {},
      onDefaultTargetLanguageSelected = {},
      onManageLanguages = {},
      onDownloadAdblockSupport = {},
      onCancelAdblockDownload = {},
      onDeleteAdblockSupport = {},
      onHowToUse = {},
    )
  }
}
