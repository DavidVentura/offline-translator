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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.davidv.translator.Gloss
import dev.davidv.translator.Language
import dev.davidv.translator.R
import dev.davidv.translator.Sense
import dev.davidv.translator.WordEntryComplete
import dev.davidv.translator.WordTag
import dev.davidv.translator.WordWithTaggedEntries
import dev.davidv.translator.wordTag

@Composable
fun DictionaryBottomSheet(
  dictionaryWord: WordWithTaggedEntries,
  dictionaryStack: List<WordWithTaggedEntries>,
  dictionaryLookupLanguage: Language,
  onDismiss: () -> Unit,
  onDictionaryLookup: (String) -> Unit = {},
  onBackPressed: () -> Unit = {},
) {
  var isVisible by remember { mutableStateOf(false) }
  var isDismissing by remember { mutableStateOf(false) }
  var selectedEntryIndex by remember(dictionaryWord) { mutableIntStateOf(0) }

  LaunchedEffect(Unit) {
    isVisible = true
  }

  val handleDismiss = {
    isDismissing = true
    isVisible = false
  }

  val handleBackPressed = {
    onBackPressed()
  }

  Box(
    modifier = Modifier.fillMaxSize(),
  ) {
    // Dimmed background
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .background(Color.Black.copy(alpha = 0.5f))
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
          ) { handleDismiss() },
    )

    // Bottom sheet content
    AnimatedVisibility(
      visible = isVisible,
      enter =
        slideInVertically(
          animationSpec = tween(300),
          initialOffsetY = { it },
        ),
      exit =
        slideOutVertically(
          animationSpec = tween(300),
          targetOffsetY = { it },
        ),
      modifier =
        Modifier
          .align(Alignment.BottomCenter)
          .navigationBarsPadding()
          .imePadding(),
    ) {
      Surface(
        modifier =
          Modifier
            .fillMaxWidth()
            .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.6f).dp)
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
      ) {
        Box(
          modifier = Modifier.fillMaxWidth(),
        ) {
          AnimatedContent(
            targetState = Pair(dictionaryWord, selectedEntryIndex),
            transitionSpec = {
              fadeIn(
                animationSpec = tween(150),
              ) togetherWith
                fadeOut(
                  animationSpec = tween(150),
                )
            },
            label = "dictionary_content",
            modifier = Modifier.fillMaxWidth(),
          ) { (currentWord, entryIndex) ->
            DictionaryEntry(
              dictionaryWord = currentWord,
              dictionaryLookupLanguage = dictionaryLookupLanguage,
              selectedEntryIndex = entryIndex,
              onEntryIndexChanged = { selectedEntryIndex = it },
              showBackButton = dictionaryStack.size > 1,
              onDictionaryLookup = onDictionaryLookup,
              onBackPressed = handleBackPressed,
            )
          }
        }
      }
    }
  }

  LaunchedEffect(isDismissing) {
    if (isDismissing) {
      kotlinx.coroutines.delay(300) // Wait for exit animation
      onDismiss()
    }
  }
}

@Composable
fun DictionaryEntry(
  dictionaryWord: WordWithTaggedEntries,
  dictionaryLookupLanguage: Language,
  selectedEntryIndex: Int,
  onEntryIndexChanged: (Int) -> Unit,
  showBackButton: Boolean = false,
  onDictionaryLookup: (String) -> Unit = {},
  onBackPressed: () -> Unit = {},
) {
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(vertical = 16.dp, horizontal = 8.dp)
        .verticalScroll(rememberScrollState())
        .semantics { contentDescription = "Dictionary Entry" }
        .testTag("DictionaryEntry"),
  ) {
    Box(
      modifier =
        Modifier
          .fillMaxWidth(),
    ) {
      if (showBackButton) {
        IconButton(
          onClick = onBackPressed,
          modifier =
            Modifier
              .align(Alignment.CenterStart)
              .size(24.dp),
        ) {
          Icon(
            painterResource(id = R.drawable.arrow_back),
            contentDescription = "Back",
            tint = MaterialTheme.colorScheme.onSurface,
          )
        }
      }

      Row(
        modifier =
          Modifier
            .padding(start = if (showBackButton) 32.dp else 0.dp),
      ) {
        Text(
          dictionaryWord.word,
          style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        )

        Spacer(Modifier.weight(1f))
        if (dictionaryWord.wordTag == WordTag.BOTH) {
          Row(
            modifier = Modifier.alignByBaseline(),
          ) {
            Text(
              dictionaryLookupLanguage.code,
              style =
                MaterialTheme.typography.titleMedium.copy(
                  fontWeight = if (selectedEntryIndex == 0) FontWeight.Bold else FontWeight.Normal,
                ),
              modifier =
                Modifier
                  .padding(4.dp)
                  .clickable { onEntryIndexChanged(0) },
            )
            Text(
              "|",
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
              modifier = Modifier.padding(4.dp),
            )
            Text(
              "en",
              style =
                MaterialTheme.typography.titleMedium.copy(
                  fontWeight = if (selectedEntryIndex == 1) FontWeight.Bold else FontWeight.Normal,
                ),
              modifier =
                Modifier
                  .padding(4.dp)
                  .clickable { onEntryIndexChanged(1) },
            )
          }
        }
      }
    }

    val ipa = dictionaryWord.sounds?.replace('[', '/')?.replace(']', '/')
    val hyphenation = dictionaryWord.hyphenations.takeIf { it.isNotEmpty() }?.joinToString("‧")

    Row {
      if (!ipa.isNullOrEmpty()) {
        Text(
          text = ipa,
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(bottom = 4.dp, end = if (hyphenation.isNullOrEmpty()) 0.dp else 16.dp),
        )
      }

      if (!hyphenation.isNullOrEmpty()) {
        Text(
          text = hyphenation,
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    }

    val selectedEntry =
      if (dictionaryWord.tag == 3 && dictionaryWord.entries.size > selectedEntryIndex) {
        dictionaryWord.entries[selectedEntryIndex]
      } else {
        dictionaryWord.entries.firstOrNull()
      }

    if (dictionaryWord.redirects.isNotEmpty()) {
      Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(
          text = "Also as:",
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(end = 4.dp),
        )
        dictionaryWord.redirects.forEachIndexed { i, r ->
          InteractiveText(
            text = r,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            onDictionaryLookup = onDictionaryLookup,
          )
          if (i < dictionaryWord.redirects.size - 1) {
            Text(
              text = ",",
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.padding(end = 2.dp),
            )
          }
        }
      }
    }

    if (selectedEntry != null) {
      WordEntryDisplay(
        entry = selectedEntry,
        onDictionaryLookup = onDictionaryLookup,
      )
    }
  }
}

@Composable
fun WordEntryDisplay(
  entry: WordEntryComplete,
  onDictionaryLookup: (String) -> Unit = {},
) {
  var lastPos: String? = null
  entry.senses.forEach { sense ->
    if (lastPos != sense.pos) {
      Text(
        text = sense.pos,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(bottom = 4.dp, top = 4.dp),
      )
      lastPos = sense.pos
    }

    sense.glosses.forEach { gloss ->
      gloss.glossLines.forEach { line ->
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(start = 10.dp)) {
          Text(
            "•",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clearAndSetSemantics { },
          )

          InteractiveText(
            text = line,
            style = MaterialTheme.typography.bodyMedium,
            onDictionaryLookup = onDictionaryLookup,
            modifier = Modifier.padding(bottom = 2.dp),
          )
        }
      }
    }
  }
}

@Preview(showBackground = true)
@Composable
fun DictionaryBottomSheetPreview() {
  // Spanish (monolingual) senses
  val spanishSenses =
    listOf(
      Sense(
        pos = "sustantivo masculino",
        glosses =
          listOf(
            Gloss(listOf("libro de consulta que contiene palabras ordenadas alfabéticamente con sus definiciones")),
            Gloss(listOf("obra que recopila y explica de forma ordenada voces de una o más lenguas")),
            Gloss(
              listOf(
                "repertorio en forma de libro donde se recogen, según un orden determinado, las palabras o expresiones de una o más lenguas",
              ),
            ),
            Gloss(listOf("catálogo de datos o noticias importantes de una materia determinada")),
          ),
      ),
      Sense(
        pos = "verbo transitivo",
        glosses = listOf(Gloss(listOf("compilar o registrar información de manera sistemática y ordenada"))),
      ),
    )

  // English senses
  val englishSenses =
    listOf(
      Sense(
        pos = "noun",
        glosses =
          listOf(
            Gloss(listOf("a reference book containing an alphabetical list of words with information about them")),
            Gloss(listOf("a book that explains the words of a language and is arranged in alphabetical order")),
            Gloss(listOf("a reference work that lists the words of a language typically in alphabetical order")),
            Gloss(listOf("a book giving information on particular subjects or on a particular class of words")),
          ),
      ),
      Sense(
        pos = "verb",
        glosses =
          listOf(
            Gloss(listOf("to compile a dictionary of")),
            Gloss(listOf("to supply with a dictionary")),
          ),
      ),
    )

  val sampleWord =
    WordWithTaggedEntries(
      word = "dictionary",
      // Both monolingual and English
      tag = 3,
      entries =
        listOf(
          // Spanish monolingual entry (first)
          WordEntryComplete(
            senses = spanishSenses,
          ),
          // English entry (second)
          WordEntryComplete(
            senses = englishSenses,
          ),
        ),
      sounds = "[dik.θjo.ˈna.ɾjo]",
      hyphenations = listOf("dic", "cio", "na", "rio"),
      redirects = listOf("book", "libro"),
    )

  MaterialTheme {
    Surface {
      DictionaryEntry(
        dictionaryWord = sampleWord,
        dictionaryLookupLanguage =
          Language(
            code = "es",
            displayName = "Spanish",
            shortDisplayName = "Spanish",
            tessName = "es",
            script = "Latn",
            dictionaryCode = "es",
            tessdataSizeBytes = 0,
          ),
        selectedEntryIndex = 0,
        onEntryIndexChanged = {},
        showBackButton = true,
      )
    }
  }
}
