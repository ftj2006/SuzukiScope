package com.suzukiscan.core.log

/** One decoded live-data sample for a field, at a point in time. */
data class Reading(val fieldId: String, val timestampMs: Long, val value: Double)

/**
 * Tracks running peak (max) values per field and buffers readings for export.
 * Not persisted/hardcoded to any particular field set — works for whatever
 * [com.suzukiscan.core.field.FieldDefinition]s are currently enabled.
 */
class LiveDataRecorder {
    private val peaks = HashMap<String, Double>()
    private val mins = HashMap<String, Double>()
    private val history = ArrayList<Reading>()

    fun record(reading: Reading) {
        history.add(reading)
        peaks[reading.fieldId] = maxOf(peaks[reading.fieldId] ?: reading.value, reading.value)
        mins[reading.fieldId] = minOf(mins[reading.fieldId] ?: reading.value, reading.value)
    }

    fun peak(fieldId: String): Double? = peaks[fieldId]
    fun min(fieldId: String): Double? = mins[fieldId]
    fun readings(): List<Reading> = history.toList()

    fun clear() {
        peaks.clear()
        mins.clear()
        history.clear()
    }

    /** CSV export — plain columns so it opens directly in Excel/Sheets/etc. or re-imports elsewhere. */
    fun toCsv(labelFor: (String) -> String = { it }, unitFor: (String) -> String = { "" }): String {
        val sb = StringBuilder("timestamp_ms,field_id,label,value,unit\n")
        for (r in history) {
            sb.append(r.timestampMs).append(',')
                .append(r.fieldId).append(',')
                .append(labelFor(r.fieldId)).append(',')
                .append(r.value).append(',')
                .append(unitFor(r.fieldId)).append('\n')
        }
        return sb.toString()
    }
}
