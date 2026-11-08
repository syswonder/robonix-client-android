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
    fun testSequenceSerialLayoutAndActivePath() {
        // Serial Sequence:
        //       Node 0 (sequence)
        //              │
        //              ▼
        //       Node 1 (action, SUCCEEDED)
        //              │
        //              ▼
        //       Node 2 (action, RUNNING)
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

        // Strictly serial: All nodes are centered on the same X spine
        assertEquals(node0.x, node1.x, 0.01f)
        assertEquals(node1.x, node2.x, 0.01f)

        // Strictly serial: Flowing downward vertically
        assertTrue("node0.y must be above node1.y", node0.y < node1.y)
        assertTrue("node1.y must be above node2.y", node1.y < node2.y)

        // Active path leading into running node (2)
        assertTrue(result.activeNodeIndices.contains(0))
        assertTrue(result.activeNodeIndices.contains(2))

        val edgeToRunning = result.edges.find { it.fromNode.node.index == 1 && it.toNode.node.index == 2 }!!
        assertTrue(edgeToRunning.isActivePath)

        // Aggregated status of parent (0) should be RUNNING because child 2 is RUNNING
        assertEquals("RUNNING", node0.status)
        assertEquals("SUCCEEDED", node1.status)
        assertEquals("RUNNING", node2.status)
    }

    @Test
    fun testParallelHorizontalLayout() {
        // Parallel layout:
        //       Node 0 (parallel)
        //          /         \
        //    Node 1 (action)   Node 2 (action)
        val n0 = RtdlNode(index = 0, kindId = 1, kind = "parallel", children = listOf(1, 2), description = "Parallel Sensors")
        val n1 = RtdlNode(index = 1, kindId = 2, kind = "action", children = emptyList(), call = CapabilityCall("c1", "camera", "stream", "start"))
        val n2 = RtdlNode(index = 2, kindId = 2, kind = "action", children = emptyList(), call = CapabilityCall("c2", "lidar", "scan", "start"))
        val plan = RtdlPlan("p3", "s3", 1, 0, listOf(n0, n1, n2))

        val result = TreeLayoutEngine.computeLayout(plan, emptyMap())

        assertEquals(3, result.nodes.size)
        assertEquals(2, result.edges.size)

        val node0 = result.nodes.find { it.node.index == 0 }!!
        val node1 = result.nodes.find { it.node.index == 1 }!!
        val node2 = result.nodes.find { it.node.index == 2 }!!

        // In parallel, children are placed horizontally side-by-side
        assertTrue("node1 should be to the left of node2", node1.x < node2.x)
        assertEquals(node1.y, node2.y, 0.01f)
        assertEquals((node1.x + node2.x) / 2f, node0.x, 0.01f)
    }
}
