package ir.payamak.app.sms

import ir.payamak.app.data.BlockRule
import ir.payamak.app.data.RuleKind

class BlockMatcher(rules: List<BlockRule>) {
    private class C(val kind: Int, val text: String, val regex: Regex?)

    private val list: List<C> = rules.filter { it.enabled }.mapNotNull { r ->
        try {
            when (r.kind) {
                RuleKind.REGEX -> C(r.kind, r.pattern, Regex(r.pattern, RegexOption.IGNORE_CASE))
                RuleKind.BODY -> C(r.kind, normText(r.pattern), null)
                else -> C(r.kind, patternCore(r.pattern), null)
            }
        } catch (e: Exception) { null }
    }

    fun isBlocked(address: String, body: String): Boolean {
        if (list.isEmpty()) return false
        val v = variants(address)
        val lower = address.trim().lowercase()
        val nb by lazy { normText(body) }
        return list.any { c ->
            when (c.kind) {
                RuleKind.EXACT -> lower == c.text || v.any { it == c.text }
                RuleKind.PREFIX -> lower.startsWith(c.text) || v.any { it.startsWith(c.text) }
                RuleKind.REGEX -> c.regex!!.containsMatchIn(address) || v.any { c.regex.containsMatchIn(it) }
                RuleKind.BODY -> c.text.isNotEmpty() && nb.contains(c.text)
                else -> false
            }
        }
    }

    companion object {
        fun normText(s: String) = Fa.toLatin(s).replace('ي', 'ی').replace('ك', 'ک')
            .filterNot { it.isWhitespace() }.lowercase()

        private fun stripDigits(d: String) = when {
            d.startsWith("0098") -> d.drop(4)
            d.startsWith("98") && d.length >= 11 -> d.drop(2)
            d.startsWith("0") -> d.drop(1)
            else -> d
        }

        fun patternCore(p: String): String {
            val t = Fa.toLatin(p.trim())
            return if (t.any { it.isLetter() }) t.lowercase() else stripDigits(t.filter { it.isDigit() })
        }

        fun variants(address: String): List<String> {
            val t = Fa.toLatin(address.trim())
            if (t.any { it.isLetter() }) return listOf(t.lowercase())
            val d = t.filter { it.isDigit() }
            val out = linkedSetOf(d)
            if (d.startsWith("0098")) out += d.drop(4)
            if (d.startsWith("98")) out += d.drop(2)
            if (d.startsWith("0")) out += d.drop(1)
            return out.toList()
        }
    }
}
