package com.robonix.client.data.local

import com.robonix.client.data.model.*
import com.robonix.client.ui.chat.PlanRecord
import org.junit.Assert.*
import org.junit.Test

class PlanHistoryCodecTest {
    private val plan = RtdlPlan("p", "s", 2, 0, listOf(RtdlNode(0, 1, "call", listOf(1),
        "op", "description", CapabilityCall("c", "provider", "contract", "move",
            mapOf("nested" to mapOf("items" to listOf(1, true, null)), "text" to "ok")))))
    private val node = RtdlNodeState(0, "SUCCEEDED", CapabilityCallResult("c", "provider", "contract",
        "move", true, "output text", ""), "p", "op", "description")
    private val batch = BatchResult("p", "s", 2, false, listOf(node))

    @Test fun nestedArgumentsAndResultRoundTrip() {
        val record = PlanRecord("p:2", plan, mapOf(0 to node), listOf(batch), 42)
        val decoded = PlanHistoryCodec.decodeRecords(PlanHistoryCodec.encodeRecords(listOf(record))).single()
        assertEquals(record, decoded)
        assertEquals(plan, PlanHistoryCodec.decodePlan(PlanHistoryCodec.encodePlan(plan)))
        assertEquals(mapOf(0 to node), PlanHistoryCodec.decodeStates(PlanHistoryCodec.encodeStates(mapOf(0 to node))))
    }

    @Test fun oldAndMalformedEntriesAreTolerated() {
        assertTrue(PlanHistoryCodec.decodeRecords("[]").isEmpty())
        val valid = PlanHistoryCodec.encodeRecords(listOf(PlanRecord("p:2", plan))).removeSurrounding("[", "]")
        assertEquals(1, PlanHistoryCodec.decodeRecords("[{\"broken\":true},$valid]").size)
        assertTrue(PlanHistoryCodec.decodeBatches("not json").isEmpty())
        assertNull(PlanHistoryCodec.decodePlan("{bad"))
        assertTrue(PlanHistoryCodec.decodeStates("{\"bad\":{}}" ).isEmpty())
    }

    @Test fun malformedStateDoesNotDiscardOtherStatesOrBatches() {
        val valid = PlanHistoryCodec.encodeStates(mapOf(0 to node)).removeSurrounding("{", "}")
        assertEquals(mapOf(0 to node), PlanHistoryCodec.decodeStates("{$valid,\"1\":{\"nodeIndex\":2,\"state\":\"FAILED\"}}"))
        val encoded = PlanHistoryCodec.encodeBatches(listOf(batch)).removeSurrounding("[", "]")
        assertEquals(listOf(batch), PlanHistoryCodec.decodeBatches("[{\"broken\":true},$encoded]"))
    }

    @Test fun recordKeyIsDerivedFromPlanAndHistoryOrderSurvives() {
        val records = listOf(PlanRecord("stale-key", plan, updatedAt = 42),
            PlanRecord("p:1", plan.copy(round = 1), updatedAt = 21))
        val decoded = PlanHistoryCodec.decodeRecords(PlanHistoryCodec.encodeRecords(records))
        assertEquals(listOf("p:2", "p:1"), decoded.map { it.key })
        assertEquals(listOf(42L, 21L), decoded.map { it.updatedAt })
    }

    @Test fun emptySuccessfulBatchRoundTrips() {
        val empty = BatchResult("p", "s", 2, false, emptyList())
        assertEquals(listOf(empty), PlanHistoryCodec.decodeBatches(PlanHistoryCodec.encodeBatches(listOf(empty))))
    }
}
