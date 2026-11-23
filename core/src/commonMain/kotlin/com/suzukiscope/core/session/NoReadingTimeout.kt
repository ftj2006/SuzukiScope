package com.suzukiscope.core.session

class NoReadingTimeout(private val timeoutMs: Long) {
    private var noDataSince: Long? = null
    private var readingAtLoss = 0L

    fun reset() {
        noDataSince = null
    }

    fun observe(now: Long, lastReading: Long, stalledForMs: Long?, diagnosticsInProgress: Boolean): Boolean {
        if (diagnosticsInProgress) {
            reset()
            return false
        }
        if (noDataSince != null && lastReading > readingAtLoss) reset()
        if (noDataSince == null && stalledForMs != null) {
            noDataSince = now - stalledForMs.coerceIn(0L, timeoutMs)
            readingAtLoss = lastReading
        }
        return noDataSince?.let { now - it >= timeoutMs } ?: false
    }
}