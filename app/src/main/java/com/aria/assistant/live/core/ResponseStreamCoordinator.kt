package com.aria.assistant.live.core

class ResponseStreamCoordinator(
    private val emitMinChars: Int = 28,
    private val emitMaxChars: Int = 120
) {
    private val pending = StringBuilder()
    private var lastRawChunk: String = ""

    @Synchronized
    fun push(rawChunk: String): List<String> {
        val cleanedRaw = rawChunk
            .replace("\r", "")
            .replace("\u0000", "")
        if (cleanedRaw.isBlank()) return emptyList()

        val delta = deriveDelta(cleanedRaw)
        if (delta.isBlank()) return emptyList()

        pending.append(delta)
        return drainSegments(force = false)
    }

    @Synchronized
    fun flush(): String? {
        val drained = drainSegments(force = true)
        if (drained.isNotEmpty()) {
            return drained.joinToString(" ").trim().ifBlank { null }
        }

        val remaining = normalize(pending.toString())
        pending.clear()
        return remaining.ifBlank { null }
    }

    @Synchronized
    fun reset() {
        pending.clear()
        lastRawChunk = ""
    }

    private fun deriveDelta(raw: String): String {
        val normalizedRaw = raw.trim()
        if (normalizedRaw.isBlank()) return ""

        val delta = when {
            normalizedRaw == lastRawChunk -> ""
            normalizedRaw.startsWith(lastRawChunk) -> normalizedRaw.removePrefix(lastRawChunk)
            lastRawChunk.startsWith(normalizedRaw) -> ""
            else -> normalizedRaw
        }

        lastRawChunk = normalizedRaw
        return normalize(delta)
    }

    private fun drainSegments(force: Boolean): List<String> {
        val out = mutableListOf<String>()

        while (pending.isNotEmpty()) {
            val current = pending.toString()
            val boundary = findBoundary(current)
            val shouldForceBySize = current.length >= emitMaxChars

            if (boundary < 0 && !force && !shouldForceBySize) {
                break
            }

            val cut = when {
                boundary >= 0 -> boundary + 1
                shouldForceBySize -> emitMaxChars
                else -> current.length
            }

            val candidate = normalize(current.substring(0, cut))
            pending.delete(0, cut)

            if (candidate.length >= emitMinChars || force || shouldForceBySize) {
                if (candidate.isNotBlank()) out += candidate
            }
        }

        if (force && pending.isNotEmpty()) {
            val rest = normalize(pending.toString())
            pending.clear()
            if (rest.isNotBlank()) out += rest
        }

        return out
    }

    private fun findBoundary(text: String): Int {
        val punctuation = charArrayOf('.', '?', '!', ';', '\n', '।')
        for (i in text.indices) {
            if (text[i] in punctuation) {
                val ahead = text.length - i - 1
                if (i + 1 >= emitMinChars || ahead <= 8) {
                    return i
                }
            }
        }
        return -1
    }

    private fun normalize(input: String): String {
        return input
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
