package com.robonix.client.ui.chat.rtdl

import com.robonix.client.data.model.CapabilityCall
import com.robonix.client.data.model.RtdlNode
import com.robonix.client.data.model.RtdlNodeState
import com.robonix.client.data.model.RtdlPlan
import org.junit.Assert.*
import org.junit.Test

class TreeLayoutEngineTest {

    @Test
    fun testEmptyPlanReturnsEmptyResult() {
        val plan = RtdlPlan(
            planId = "p0",
            sessionId = "s0",
            round = 1,
            rootIndex = 0,
            nodes = emptyList(),
        )
        val result = TreeLayoutEngine.computeLayout(plan, emptyMap())
        assertTrue(result.nodes.isEmpty())
        assertTrue(result.edges.isEmpty())
    }

    @Test
    fun testSingleRootNode() {
        val root = RtdlNode(index = 0, kindId = 1, kind = "sequence", children = emptyList(), description = "Root")
        val plan = RtdlPlan("p1", "s1", 1, 0, listOf(root))
        val result = TreeLayoutEngine.computeLayout(plan, emptyMap())

        assertEquals(1, result.nodes.size)
        assertEquals(0, result.edges.size)
        assertEquals(0, result.nodes[0].node.index)
        assertEquals(0, result.nodes[0].depth)
        assertEquals(TreeLayoutEngine.DEFAULT_NODE_WIDTH / 2f, result.nodes[0].x, 0.01f)
    }

    @Test
    fun testTreeHierarchyAndActivePath() {
        // Tree:
        //       Node 0 (sequence)
        //      /                 \
        // Node 1 (action)      Node 2 (action, RUNNING)
        val n0 = RtdlNode(index = 0, kindId = 1, kind = "sequence", children = listOf(1, 2), description = "Main Sequence")
        val n1 = RtdlNode(
            index = 1,
            kindId = 2,
            kind = "action",
            children = emptyList(),
            call = CapabilityCall("c1", "arm", "reach", "reachTarget"),
        )
        val n2 = RtdlNode(
            index = 2,
            kindId = 2,
            kind = "action",
            children = emptyList(),
            call = CapabilityCall("c2", "gripper", "grasp", "graspObject"),
        )
        val plan = RtdlPlan("p2", "s2", 1, 0, listOf(n0, n1, n2))

        val nodeStates = mapOf(
            1 to RtdlNodeState(1, "SUCCEEDED"),
            2 to RtdlNodeState(2, "RUNNING"),
        )

        val result = TreeLayoutEngine.computeLayout(plan, nodeStates)

        assertEquals(3, result.nodes.size)
        assertEquals(2, result.edges.size)

        val node0 = result.nodes.find { it.node.index == 0 }!!
        val node1 = result.nodes.find { it.node.index == 1 }!!
        val node2 = result.nodes.find { it.node.index == 2 }!!

        // Node 0 should be at depth 0, children at depth 1
        assertEquals(0, node0.depth)
        assertEquals(1, node1.depth)
        assertEquals(1, node2.depth)

        // Node 0 should be horizontally centered between node 1 and node 2
        assertEquals((node1.x + node2.x) / 2f, node0.x, 0.01f)

        // Active path should be root (0) -> running node (2)
        assertTrue(result.activeNodeIndices.contains(0))
        assertTrue(result.activeNodeIndices.contains(2))
        assertFalse(result.activeNodeIndices.contains(1))

        val edgeToRunning = result.edges.find { it.fromNode.node.index == 0 && it.toNode.node.index == 2 }!!
        val edgeToDone = result.edges.find { it.fromNode.node.index == 0 && it.toNode.node.index == 1 }!!

        assertTrue(edgeToRunning.isActivePath)
        assertFalse(edgeToDone.isActivePath)

        // Aggregated status of parent (0) should be RUNNING because child 2 is RUNNING
        assertEquals("RUNNING", node0.status)
        assertEquals("SUCCEEDED", node1.status)
        assertEquals("RUNNING", node2.status)
    }
}

