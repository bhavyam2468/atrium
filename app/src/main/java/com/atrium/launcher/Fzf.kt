package com.atrium.launcher

import kotlin.math.abs

/**
 * Atrium Fuzzy Matcher
 * Supports exact substring / prefix matching and strict 1-degree error tolerance
 * (Levenshtein distance = 1 for typos: 1 insertion, 1 deletion, or 1 substitution).
 * Rejects matches with >= 2 errors.
 */
object Fzf {

    fun score(pattern: CharSequence, target: CharSequence): Int? {
        val pLen = pattern.length
        val tLen = target.length
        if (pLen == 0) return 0
        if (pLen > tLen + 1) return null

        val p = pattern.toString().lowercase()
        val t = target.toString().lowercase()

        // 1. Exact substring check (0 errors)
        val subIdx = t.indexOf(p)
        if (subIdx >= 0) {
            val prefixBonus = if (subIdx == 0) 40 else 0
            val wordStartBonus = if (subIdx > 0 && isWordBoundary(t[subIdx - 1])) 25 else 0
            return 120 + prefixBonus + wordStartBonus + (pLen * 10) - (subIdx * 3)
        }

        // 2. Strict 1-degree error tolerance (distance == 1) against target or words in target
        val words = extractWords(t)
        var best1ErrorScore: Int? = null

        for ((wIdx, word) in words.withIndex()) {
            val wLen = word.length
            for (len in (pLen - 1)..(pLen + 1)) {
                if (len <= 0 || len > wLen) continue
                val candidate = word.substring(0, len)
                if (isLevenshtein1(p, candidate)) {
                    val score = 80 + (pLen * 8) - (wIdx * 6)
                    if (best1ErrorScore == null || score > best1ErrorScore) {
                        best1ErrorScore = score
                    }
                }
            }
        }

        return best1ErrorScore
    }

    private fun isWordBoundary(c: Char): Boolean =
        c == ' ' || c == '.' || c == '-' || c == '_' || c == '/' || c == '(' || c == '['

    private fun extractWords(text: String): List<String> {
        val list = ArrayList<String>(4)
        list.add(text) // full target first
        val parts = text.split(' ', '-', '_', '.', '/')
        for (part in parts) {
            if (part.isNotEmpty() && part != text) {
                list.add(part)
            }
        }
        return list
    }

    /**
     * Returns true if and only if the Levenshtein distance between s1 and s2 is exactly 1.
     */
    private fun isLevenshtein1(s1: String, s2: String): Boolean {
        val l1 = s1.length
        val l2 = s2.length
        if (abs(l1 - l2) > 1) return false

        if (l1 == l2) {
            // Exactly 1 substitution
            var diff = 0
            for (i in 0 until l1) {
                if (s1[i] != s2[i]) {
                    diff++
                    if (diff > 1) return false
                }
            }
            return diff == 1
        }

        // 1 insertion or deletion
        val longer = if (l1 > l2) s1 else s2
        val shorter = if (l1 > l2) s2 else s1
        var i = 0
        var j = 0
        var diff = 0

        while (i < longer.length && j < shorter.length) {
            if (longer[i] != shorter[j]) {
                diff++
                if (diff > 1) return false
                i++
            } else {
                i++
                j++
            }
        }
        return true
    }
}
