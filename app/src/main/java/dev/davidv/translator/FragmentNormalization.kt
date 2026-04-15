package dev.davidv.translator

internal fun normalizeFragmentText(text: String): String =
  text
    .replace(Regex("\\s+"), " ")
    .replace(Regex("\\s+([.,;:!?])"), "$1")
    .trim()
    .lowercase()

internal fun areEquivalentFragmentTexts(
  first: String,
  second: String,
): Boolean = normalizeFragmentText(first) == normalizeFragmentText(second)

internal fun <T> dedupeFragmentsByBoundsAndText(
  fragments: List<T>,
  boundsKey: (T) -> String,
  text: (T) -> String,
): List<T> {
  val deduped = fragments.distinctBy { "${boundsKey(it)}|${normalizeFragmentText(text(it))}" }
  return deduped.groupBy(boundsKey).values.map { it.first() }
}
