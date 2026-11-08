package com.junho.sfcalllog

/** "아임 웹", "(주)아임웹", "IMWEB" 처럼 조금 다르게 입력해도 찾기 위한 유사도 계산 */
object Fuzzy {
    fun normalize(s: String): String =
        s.lowercase()
            .replace("(주)", "").replace("㈜", "").replace("주식회사", "")
            .filter { it.isLetterOrDigit() }

    /** 0.0 ~ 1.0 (1이 완전히 같음) */
    fun similarity(q: String, target: String): Double {
        if (q.isEmpty() || target.isEmpty()) return 0.0
        if (q == target) return 1.0
        if (target.startsWith(q)) return 0.95
        if (target.contains(q)) return 0.9
        if (q.contains(target) && target.length >= 2) return 0.8
        val lev = 1.0 - levenshtein(q, target).toDouble() / maxOf(q.length, target.length)
        val bi = bigramDice(q, target)
        return maxOf(lev, bi) * 0.85
    }

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }

    private fun bigramDice(a: String, b: String): Double {
        if (a.length < 2 || b.length < 2) return 0.0
        val ba = a.windowed(2).toMutableList()
        val bb = b.windowed(2)
        var hit = 0
        for (g in bb) {
            val idx = ba.indexOf(g)
            if (idx >= 0) { hit++; ba.removeAt(idx) }
        }
        return 2.0 * hit / (a.length - 1 + b.length - 1)
    }
}
