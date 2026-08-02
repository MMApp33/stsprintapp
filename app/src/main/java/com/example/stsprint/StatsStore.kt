package com.example.stsprint

import java.util.concurrent.atomic.AtomicLong

class StatsStore {
    private val printJobCountRef = AtomicLong(0)
    private val callCountRef = AtomicLong(0)
    private val startTimeMs = System.currentTimeMillis()

    fun recordPrintJob() {
        printJobCountRef.incrementAndGet()
    }

    fun recordCall() {
        callCountRef.incrementAndGet()
    }

    fun getPrintJobCount(): Long = printJobCountRef.get()

    fun getCallCount(): Long = callCountRef.get()

    fun getUptimeSeconds(): Long = (System.currentTimeMillis() - startTimeMs) / 1000

    fun getUptimeString(): String {
        val seconds = getUptimeSeconds()
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60

        return when {
            days > 0 -> "$days day${if (days > 1) "s" else ""}, $hours hr"
            hours > 0 -> "$hours hr, $minutes min"
            else -> "$minutes min"
        }
    }

    fun reset() {
        printJobCountRef.set(0)
        callCountRef.set(0)
    }
}
