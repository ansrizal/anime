    private fun solvePoW(nonce: String, difficulty: Int, timeoutMs: Long = 30_000): String? {
        if (difficulty <= 0) return "0"
        val prefix = "$nonce:"
        val startTime = System.currentTimeMillis()
        var counter = 0L

        while (true) {
            val input = (prefix + counter).toByteArray(Charsets.UTF_8)
            val hash = powHash(input)
            if (leadingZeroBits(hash) >= difficulty) {
                return counter.toString()
            }
            counter++

            // Cek timeout tiap 4096 iterasi
            if ((counter and 0xFFFL) == 0L) {
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    println("[FilmApik] solvePoW timeout after $counter tries")
                    return null
                }
            }
        }
    }
