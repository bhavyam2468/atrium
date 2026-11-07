package com.atrium.launcher

import kotlin.math.max

/**
 * High-performance port of the fzf v2 fuzzy matching algorithm.
 * Zero-allocation during matching passes for low latency and zero GC stutter.
 */
object Fzf {
    private const val SCORE_MATCH = 16
    private const val SCORE_GAP_START = -3
    private const val SCORE_GAP_EXTENSION = -1
    private const val BONUS_BOUNDARY = SCORE_MATCH / 2
    private const val BONUS_NON_WORD = SCORE_MATCH / 2
    private const val BONUS_CAMEL_123 = BONUS_BOUNDARY - 1
    private const val BONUS_CONSECUTIVE = -(SCORE_GAP_START + SCORE_GAP_EXTENSION)
    private const val BONUS_FIRST_CHAR_MULTIPLIER = 2

    // Reusable buffers on the main thread for queries up to 128 chars and targets up to 1024 chars
    private val h = IntArray(1024)
    private val c = IntArray(1024)
    private val bonus = IntArray(1024)

    /**
     * Scores how well [pattern] matches [target].
     * Returns null if [pattern] is not a subsequence of [target].
     * Higher score means a better match.
     */
    @Synchronized
    fun score(pattern: CharSequence, target: CharSequence): Int? {
        val pLen = pattern.length
        val tLen = target.length

        if (pLen == 0) return 0
        if (pLen > tLen || pLen > 128 || tLen > 1024) return null

        // 1. Quick forward check: verify pattern is a subsequence
        var pIdx = 0
        var tIdx = 0
        while (pIdx < pLen && tIdx < tLen) {
            val pChar = pattern[pIdx].lowercaseChar()
            val tChar = target[tIdx].lowercaseChar()
            if (pChar == tChar) pIdx++
            tIdx++
        }
        if (pIdx < pLen) return null // Pattern characters not found in order

        // 2. Pre-calculate bonus for each position in target
        var prevClass = CharClass.NON_WORD
        for (i in 0 until tLen) {
            val ch = target[i]
            val curClass = charClassOf(ch)
            bonus[i] = when {
                prevClass == CharClass.NON_WORD && curClass != CharClass.NON_WORD -> BONUS_BOUNDARY
                prevClass == CharClass.LOWER && curClass == CharClass.UPPER -> BONUS_CAMEL_123
                prevClass == CharClass.LETTER && curClass == CharClass.NUMBER -> BONUS_CAMEL_123
                curClass == CharClass.NON_WORD -> BONUS_NON_WORD
                else -> 0
            }
            prevClass = curClass
        }

        // 3. Dynamic Programming Matrix (Smith-Waterman with affine gaps)
        val p0 = pattern[0].lowercaseChar()
        var maxScore = 0

        for (j in 0 until tLen) {
            val tj = target[j].lowercaseChar()
            if (p0 == tj) {
                var b = bonus[j]
                if (j == 0) b *= BONUS_FIRST_CHAR_MULTIPLIER
                h[j] = SCORE_MATCH + b
                c[j] = h[j]
                if (pLen == 1 && h[j] > maxScore) maxScore = h[j]
            } else {
                h[j] = if (j > 0) h[j - 1] + SCORE_GAP_EXTENSION else SCORE_GAP_START
                c[j] = 0
            }
        }
        if (pLen == 1) return maxScore

        for (i in 1 until pLen) {
            val pi = pattern[i].lowercaseChar()
            var prevH = 0
            var inGap = false

            for (j in 0 until tLen) {
                val tj = target[j].lowercaseChar()
                val currentH = h[j]
                var s = 0

                if (pi == tj && j >= i) {
                    val prevDiagH = if (j > 0) prevH else 0
                    val prevDiagC = if (j > 0) c[j - 1] else 0

                    var matchScore = SCORE_MATCH + bonus[j]
                    if (prevDiagC > 0) {
                        // Consecutive bonus
                        matchScore = max(matchScore, prevDiagC + SCORE_MATCH + BONUS_CONSECUTIVE)
                    }
                    s = max(s, prevDiagH + SCORE_MATCH + bonus[j])
                    s = max(s, matchScore)
                    c[j] = s
                } else {
                    c[j] = 0
                }

                val leftH = if (j > 0) h[j - 1] else Int.MIN_VALUE / 2
                val gapScore = if (inGap) leftH + SCORE_GAP_EXTENSION else leftH + SCORE_GAP_START
                if (s < gapScore) {
                    s = gapScore
                    inGap = true
                } else {
                    inGap = false
                }

                prevH = currentH
                h[j] = s
                if (i == pLen - 1 && s > maxScore) {
                    maxScore = s
                }
            }
        }

        return maxScore
    }

    private enum class CharClass { LOWER, UPPER, LETTER, NUMBER, NON_WORD }

    private fun charClassOf(c: Char): CharClass = when {
        c in 'a'..'z' -> CharClass.LOWER
        c in 'A'..'Z' -> CharClass.UPPER
        c in '0'..'9' -> CharClass.NUMBER
        c.isLetter() -> CharClass.LETTER
        else -> CharClass.NON_WORD
    }
}
