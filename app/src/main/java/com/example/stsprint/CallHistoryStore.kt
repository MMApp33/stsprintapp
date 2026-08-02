package com.example.stsprint

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

data class CallRecord(
    val timestamp: String,
    val timestampMs: Long,
    val phone: String,
    val id: Long = System.currentTimeMillis()
)

class CallHistoryStore {
    companion object {
        const val MAX_CALL_HISTORY = 200
    }

    private val calls = CopyOnWriteArrayList<CallRecord>()
    private val callCountRef = AtomicLong(0)

    fun addCall(phone: String) {
        val call = CallRecord(
            timestamp = CallerIdFormat.isoNow(),
            timestampMs = System.currentTimeMillis(),
            phone = phone
        )
        calls.add(0, call) // Add to front
        callCountRef.incrementAndGet()

        while (calls.size > MAX_CALL_HISTORY) {
            calls.removeAt(calls.size - 1)
        }
    }

    fun getCalls(): List<CallRecord> = calls.toList()

    fun getCallCount(): Long = callCountRef.get()

    fun clearHistory() {
        calls.clear()
    }

    fun deleteCall(id: Long) {
        calls.removeAll { it.id == id }
    }

    fun getLastCallTime(): String? = calls.firstOrNull()?.timestamp
}
