package com.ansrizal.anime

class MissAVDecoder {
    companion object {
        /**
         * Robust implementation of the Packer (P.A.C.K.E.R) deobfuscator.
         * Handles various formats of the packed string and dictionary.
         */
        fun unpack(packed: String): String {
            // Find the components: p, a, c, k
            // Pattern 1: Dictionary is a string with .split('|')
            val pattern1 = Regex("""eval\(function\(p,a,c,k,e,d\)\{.*\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""")
            // Pattern 2: Dictionary is an array [...]
            val pattern2 = Regex("""eval\(function\(p,a,c,k,e,d\)\{.*\}\('(.*?)',(\d+),(\d+),\[(.*?)\]""")

            val match1 = pattern1.find(packed)
            val match2 = pattern2.find(packed)

            val (p, aStr, cStr, kStr) = when {
                match1 != null -> match1.destructured
                match2 != null -> match2.destructured
                else -> return ""
            }

            val a = aStr.toInt()
            var c = cStr.toInt()
            val k = if (match1 != null) {
                kStr.split("|")
            } else {
                kStr.split(",").map { it.trim().removeSurrounding("'").removeSurrounding("\"") }
            }

            fun toString(num: Int, radix: Int): String {
                return Integer.toString(num, radix)
            }

            var result = p
            // We iterate downwards to avoid replacing partial tokens
            for (i in c - 1 downTo 0) {
                val word = k.getOrNull(i)
                if (!word.isNullOrBlank()) {
                    val search = toString(i, a)
                    result = result.replace(Regex("\\b$search\\b"), word)
                }
            }
            return result
        }

        /**
         * Extracts the video UUID from the page source using multiple fallback methods.
         */
        fun extractUuid(html: String): String? {
            // Fallback 1: Direct UUID pattern in scripts (often in thumbnail/seek lists)
            val uuidRegex = Regex("""surrit\.com/([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})""")
            val directMatch = uuidRegex.find(html)
            if (directMatch != null) return directMatch.groupValues[1]

            // Fallback 2: Extract from unpacked Packer JavaScript
            val unpacked = unpack(html)
            if (unpacked.isNotBlank()) {
                val surritMatch = Regex("""surrit\.com/([a-f0-9-]+)/""").find(unpacked)
                if (surritMatch != null) return surritMatch.groupValues[1]
            }

            return null
        }
    }
}
