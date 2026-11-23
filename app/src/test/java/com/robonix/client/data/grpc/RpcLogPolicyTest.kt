package com.robonix.client.data.grpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcLogPolicyTest {
    private val key = "Atlas/Query @10.0.0.1:50051"

    @Test
    fun logsFirstResultThenStaysQuietOnRepeats() {
        val policy = RpcLogPolicy()
        assertEquals(listOf("$key OK 12ms (first)"), policy.unaryClosed(key, "OK", "OK", 12, 0))
        assertEquals(emptyList<String>(), policy.unaryClosed(key, "OK", "OK", 15, 3_000))
    }

    @Test
    fun logsFailureAndRecoveryButNotRepeatedFailures() {
        val policy = RpcLogPolicy()
        policy.unaryClosed(key, "OK", "OK", 10, 0)
        assertEquals(
            listOf("$key UNAVAILABLE: io 3ms (was OK)"),
            policy.unaryClosed(key, "UNAVAILABLE", "UNAVAILABLE: io", 3, 1_000),
        )
        assertEquals(emptyList<String>(), policy.unaryClosed(key, "UNAVAILABLE", "UNAVAILABLE: io", 4, 2_000))
        assertEquals(listOf("$key OK 9ms (was UNAVAILABLE)"), policy.unaryClosed(key, "OK", "OK", 9, 3_000))
    }

    @Test
    fun logsSlowRepeats() {
        val policy = RpcLogPolicy(slowMs = 2_000)
        policy.unaryClosed(key, "OK", "OK", 10, 0)
        assertEquals(listOf("$key OK 2500ms (slow)"), policy.unaryClosed(key, "OK", "OK", 2_500, 1_000))
    }

    @Test
    fun summarisesEachWindowAndResetsCounts() {
        val policy = RpcLogPolicy(summaryEveryMs = 60_000)
        policy.unaryClosed(key, "OK", "OK", 10, 0)
        policy.unaryClosed(key, "OK", "OK", 30, 20_000)
        val lines = policy.unaryClosed(key, "UNAVAILABLE", "UNAVAILABLE", 20, 60_000)
        assertEquals(
            listOf("$key UNAVAILABLE 20ms (was OK)", "summary 60s $key ok=2 fail=1 avg=20ms max=30ms last=UNAVAILABLE"),
            lines,
        )
        val next = policy.unaryClosed(key, "UNAVAILABLE", "UNAVAILABLE", 5, 120_000)
        assertTrue(next.single().startsWith("summary 60s $key ok=0 fail=1 "))
    }
}
