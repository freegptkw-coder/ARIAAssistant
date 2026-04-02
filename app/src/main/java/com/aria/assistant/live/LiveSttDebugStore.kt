package com.aria.assistant.live

import android.content.Context

data class SttDebugStatus(
    val availabilityStatus: String,
    val failureCount: Int,
    val timeoutCount: Int,
    val unavailableCount: Int,
    val lastRetryDelayMs: Long,
    val retryActive: Boolean,
    val lastSuccessAtMs: Long,
    val voiceState: String,
    val updatedAtMs: Long
)

object LiveSttDebugStore {
    private const val PREF = "ARIA_PREFS"

    private const val KEY_STT_AVAILABILITY = "stt_debug_availability"
    private const val KEY_STT_FAILURE_COUNT = "stt_debug_failure_count"
    private const val KEY_STT_TIMEOUT_COUNT = "stt_debug_timeout_count"
    private const val KEY_STT_UNAVAILABLE_COUNT = "stt_debug_unavailable_count"
    private const val KEY_STT_LAST_RETRY_DELAY_MS = "stt_debug_last_retry_delay_ms"
    private const val KEY_STT_RETRY_ACTIVE = "stt_debug_retry_active"
    private const val KEY_STT_LAST_SUCCESS_AT_MS = "stt_debug_last_success_at_ms"
    private const val KEY_STT_VOICE_STATE = "stt_debug_voice_state"
    private const val KEY_STT_UPDATED_AT_MS = "stt_debug_updated_at_ms"

    fun read(context: Context): SttDebugStatus {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return SttDebugStatus(
            availabilityStatus = prefs.getString(KEY_STT_AVAILABILITY, "idle").orEmpty(),
            failureCount = prefs.getInt(KEY_STT_FAILURE_COUNT, 0),
            timeoutCount = prefs.getInt(KEY_STT_TIMEOUT_COUNT, 0),
            unavailableCount = prefs.getInt(KEY_STT_UNAVAILABLE_COUNT, 0),
            lastRetryDelayMs = prefs.getLong(KEY_STT_LAST_RETRY_DELAY_MS, 0L),
            retryActive = prefs.getBoolean(KEY_STT_RETRY_ACTIVE, false),
            lastSuccessAtMs = prefs.getLong(KEY_STT_LAST_SUCCESS_AT_MS, 0L),
            voiceState = prefs.getString(KEY_STT_VOICE_STATE, "idle").orEmpty(),
            updatedAtMs = prefs.getLong(KEY_STT_UPDATED_AT_MS, 0L)
        )
    }

    fun write(context: Context, status: SttDebugStatus) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STT_AVAILABILITY, status.availabilityStatus)
            .putInt(KEY_STT_FAILURE_COUNT, status.failureCount)
            .putInt(KEY_STT_TIMEOUT_COUNT, status.timeoutCount)
            .putInt(KEY_STT_UNAVAILABLE_COUNT, status.unavailableCount)
            .putLong(KEY_STT_LAST_RETRY_DELAY_MS, status.lastRetryDelayMs)
            .putBoolean(KEY_STT_RETRY_ACTIVE, status.retryActive)
            .putLong(KEY_STT_LAST_SUCCESS_AT_MS, status.lastSuccessAtMs)
            .putString(KEY_STT_VOICE_STATE, status.voiceState)
            .putLong(KEY_STT_UPDATED_AT_MS, status.updatedAtMs)
            .apply()
    }

    fun clear(context: Context) {
        write(
            context,
            SttDebugStatus(
                availabilityStatus = "idle",
                failureCount = 0,
                timeoutCount = 0,
                unavailableCount = 0,
                lastRetryDelayMs = 0L,
                retryActive = false,
                lastSuccessAtMs = 0L,
                voiceState = "idle",
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }
}