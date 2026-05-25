package com.example.data

import java.util.Locale

object HebrewSearchEngine {
    private val TOKEN_REGEX = Regex("[\\w\\u0590-\\u05ff]+")

    private val GEMATRIA_LETTERS = "אבגדהוזחטיכךלמםנןסעפףצץקרשת".toSet()
    private val TENS_LETTERS = listOf("י", "כ", "ל", "מ", "נ", "ס", "ע", "פ", "צ")
    private val UNITS_LETTERS = listOf("א", "ב", "ג", "ד", "ה", "ו", "ז", "ח", "ט")
    private val HUNDREDS_LETTERS = listOf("ק", "ר", "ש", "ת")
    private val SPECIAL_TWO = setOf("טו", "טז")

    private val VALID_GEMATRIA_TERMS: Set<String> by lazy {
        val valid = mutableSetOf<String>()
        // Single letters
        for (letter in GEMATRIA_LETTERS) {
            valid.add(letter.toString())
        }
        // Two-letter combinations
        valid.addAll(SPECIAL_TWO)
        for (tens in TENS_LETTERS) {
            for (unit in UNITS_LETTERS) {
                val combo = tens + unit
                if (combo == "יה" || combo == "יו") continue
                valid.add(combo)
            }
        }
        for (hundreds in HUNDREDS_LETTERS) {
            for (unit in UNITS_LETTERS) {
                valid.add(hundreds + unit)
            }
            for (tens in TENS_LETTERS) {
                valid.add(hundreds + tens)
            }
        }
        // Three-letter combinations
        for (hundreds in HUNDREDS_LETTERS) {
            for (tens in TENS_LETTERS) {
                for (unit in UNITS_LETTERS) {
                    val combo = hundreds + tens + unit
                    if (tens == "י" && (unit == "ה" || unit == "ו")) continue
                    valid.add(combo)
                }
            }
            for (special in SPECIAL_TWO) {
                valid.add(hundreds + special)
            }
        }
        valid
    }

    private fun getTokens(text: String): List<String> {
        return TOKEN_REGEX.findAll(text).map { it.value.lowercase(Locale.ROOT) }.toList()
    }

    private fun isOrderedSubsequence(needle: String, haystack: String): Boolean {
        var position = 0
        for (ch in needle) {
            position = haystack.indexOf(ch, position)
            if (position == -1) return false
            position++
        }
        return true
    }

    private fun isGematriaTerm(term: String): Boolean {
        if (term.isEmpty() || term.length > 3) return false
        if (!term.all { it in GEMATRIA_LETTERS }) return false
        return term in VALID_GEMATRIA_TERMS
    }

    /**
     * Matches filter terms (e.g., entered by user) against the search path text.
     * Replicates search_match.py's double-colon-separated chapter lookup logic.
     */
    fun orderedTermsMatch(searchText: String, filterText: String): Boolean {
        val terms = filterText.split(" ").map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
        if (terms.isEmpty()) return true

        val segments = if (searchText.contains("::")) searchText.split("::") else listOf(searchText)
        val taggedTokens = mutableListOf<Pair<String, Boolean>>() // Token text , isChapterSegment
        val lastIndex = segments.size - 1
        val chapterIndex = lastIndex - 1 // The second-to-last segment (usually numbering)

        for (segIndex in segments.indices) {
            val isChapter = segIndex == chapterIndex
            for (token in getTokens(segments[segIndex])) {
                taggedTokens.add(token to isChapter)
            }
        }

        var tokenIndex = 0
        val hasChapterSegment = taggedTokens.any { it.second }

        for (termIndex in terms.indices) {
            val term = terms[termIndex]
            val isChapterGematriaLookup = hasChapterSegment &&
                    terms.size > 1 &&
                    termIndex == terms.size - 1 &&
                    isGematriaTerm(term)

            var matched = false
            while (tokenIndex < taggedTokens.size) {
                val (token, inChapter) = taggedTokens[tokenIndex]
                if (isChapterGematriaLookup) {
                    if (inChapter && token.startsWith(term)) {
                        matched = true
                        tokenIndex++
                        break
                    }
                } else {
                    if (isOrderedSubsequence(term, token)) {
                        matched = true
                        tokenIndex++
                        break
                    }
                }
                tokenIndex++
            }
            if (!matched) return false
        }
        return true
    }
}
