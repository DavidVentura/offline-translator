package dev.davidv.translator

typealias Gloss = uniffi.bindings.DictionaryGlossRecord
typealias Sense = uniffi.bindings.DictionarySenseRecord
typealias WordEntryComplete = uniffi.bindings.DictionaryWordEntryRecord
typealias WordWithTaggedEntries = uniffi.bindings.DictionaryWordRecord

enum class WordTag(
  val value: Int,
) {
  MONOLINGUAL(1),
  ENGLISH(2),
  BOTH(3),
  ;

  companion object {
    fun fromValue(value: Int): WordTag? = entries.find { it.value == value }
  }
}

val WordWithTaggedEntries.wordTag: WordTag
  get() = WordTag.fromValue(tag)!!
