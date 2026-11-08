package com.robonix.client.ui.chat.rtdl

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.robonix.client.data.model.RtdlNode
import com.robonix.client.data.model.RtdlNodeState
import com.robonix.client.data.model.RtdlPlan
import kotlin.math.max

data class LayoutNode(
    val node: RtdlNode,
    val depth: Int,
    val x: Float, // Center X in dp
    val y: Float, // Center Y in dp
    val width: Float = 130f,
    val height: Float = 42f,
    val status: String,
    val stateObj: RtdlNodeState?,
    val parentIndex: Int?,
    val childrenIndices: List<Int>,
) {
    val left: Float get() = x - width / 2f
    val top: Float get() = y - height / 2f
    val right: Float get() = x + width / 2f
    val bottom: Float get() = y + height / 2f
    val bounds: Rect get() = Rect(left, top, right, bottom)
    val topCenter: Offset get() = Offset(x, top)
    val bottomCenter: Offset get() = Offset(x, bottom)
}

data class LayoutEdge(
    val fromNode: LayoutNode,
    val toNode: LayoutNode,
    val isActivePath: Boolean,
)

data class TreeLayoutResult(
    val nodes: List<LayoutNode>,
    val edges: List<LayoutEdge>,
    val bounds: Rect,
    val activeNodeIndices: Set<Int>,
)

object TreeLayoutEngine {
    const val DEFAULT_NODE_WIDTH = 132f
    const val DEFAULT_NODE_HEIGHT = 44f
    const val DEFAULT_H_GAP = 24f
    const val DEFAULT_V_GAP = 52f

    /**
     * Resolves node statuses matching the web client and RtdlScreen:
     * leaf nodes use their reported state; composite nodes aggregate from children.
     */
    fun aggregateNodeStatuses(
        plan: RtdlPlan,
        nodeStates: Map<Int, RtdlNodeState>,
    ): Map<Int, String> {
        val nodes = plan.nodes
        if (nodes.isEmpty()) return emptyMap()
        val byIndex = nodes.associateBy { it.index }
        val resolved = mutableMapOf<Int, String>()

        // Post-order iterative traversal
        val inDegree = mutableMapOf<Int, Int>()
        nodes.forEach { n ->
            n.children.filter { it in byIndex }.forEach { c ->
                inDegree[c] = (inDegree[c] ?: 0) + 1
            }
        }

        val childMap = nodes.associate { n -> n.index to n.children.filter { it in byIndex } }
        val leaves = nodes.filter { childMap[it.index].isNullOrEmpty() }.map { it.index }.toMutableList()
        val remainingChildren = childMap.mapValues { it.value.toMutableSet() }.toMutableMap()

        // Leaves first
        while (leaves.isNotEmpty()) {
            val idx = leaves.removeAt(0)
            val ownState = nodeStates[idx]?.state
            if (ownState != null) {
                resolved[idx] = ownState
            } else {
                val children = childMap[idx] ?: emptyList()
                if (children.isEmpty()) {
                    resolved[idx] = "PENDING"
                } else {
                    val childStatuses = children.map { resolved[it] ?: "PENDING" }
                    resolved[idx] = when {
                        childStatuses.any { it in listOf("FAILED", "ERROR") } -> "FAILED"
                        childStatuses.any { it in listOf("RUNNING", "ACTIVE", "VERIFYING") } -> "RUNNING"
                        childStatuses.all { it in listOf("SUCCEEDED", "SUCCESS") } -> "SUCCEEDED"
                        else -> "PENDING"
                    }
                }
            }

            nodes.forEach { parent ->
                val rem = remainingChildren[parent.index]
                if (rem != null && rem.remove(idx) && rem.isEmpty()) {
                    leaves.add(parent.index)
                }
            }
        }

        // Fill in any remaining nodes (e.g. cycles or unreached components)
        nodes.forEach { n ->
            if (n.index !in resolved) {
                resolved[n.index] = nodeStates[n.index]?.state ?: "PENDING"
            }
        }

        return resolved
    }

    /**
     * Computes 2D coordinates and edges for the behavior tree.
     */
    fun computeLayout(
        plan: RtdlPlan,
        nodeStates: Map<Int, RtdlNodeState>,
        nodeWidth: Float = DEFAULT_NODE_WIDTH,
        nodeHeight: Float = DEFAULT_NODE_HEIGHT,
        hGap: Float = DEFAULT_H_GAP,
        vGap: Float = DEFAULT_V_GAP,
    ): TreeLayoutResult {
        if (plan.nodes.isEmpty()) {
            return TreeLayoutResult(emptyList(), emptyList(), Rect(0f, 0f, 0f, 0f), emptySet())
        }

        val byIndex = plan.nodes.associateBy { it.index }
        val childSet = plan.nodes.flatMap { it.children }.toSet()
        val aggregatedStatus = aggregateNodeStatuses(plan, nodeStates)

        // Determine root nodes
        val roots = mutableListOf<RtdlNode>()
        if (plan.rootIndex in byIndex) {
            roots.add(byIndex[plan.rootIndex]!!)
        }
        plan.nodes.forEach { node ->
            if (node.index !in childSet && node !in roots) {
                roots.add(node)
            }
        }
        if (roots.isEmpty()) {
            roots.add(plan.nodes.first())
        }

        // Build subtree layout trees
        class SubtreeBox(
            val node: RtdlNode,
            val children: List<SubtreeBox>,
            var width: Float,
            var relativeCenterOffset: Float, // Center X relative to subtree's left edge
            var parentIndex: Int? = null,
        )

        val visited = mutableSetOf<Int>()

        fun buildBox(node: RtdlNode, parentIdx: Int?): SubtreeBox {
            visited.add(node.index)
            val validChildren = node.children.mapNotNull { byIndex[it] }.filter { it.index !in visited }
            val childBoxes = validChildren.map { buildBox(it, node.index) }

            if (childBoxes.isEmpty()) {
                return SubtreeBox(node, emptyList(), nodeWidth, nodeWidth / 2f, parentIdx)
            }

            val totalChildrenWidth = childBoxes.sumOf { it.width.toDouble() }.toFloat() +
                (childBoxes.size - 1) * hGap

            val subtreeWidth = max(nodeWidth, totalChildrenWidth)

            // Center of the first and last child to calculate parent center
            val childrenSpanStart = if (totalChildrenWidth < subtreeWidth) {
                (subtreeWidth - totalChildrenWidth) / 2f
            } else 0f

            var runningX = childrenSpanStart
            val childCenters = childBoxes.map { cBox ->
                val cCenter = runningX + cBox.relativeCenterOffset
                runningX += cBox.width + hGap
                cCenter
            }

            val parentCenter = (childCenters.first() + childCenters.last()) / 2f

            return SubtreeBox(node, childBoxes, subtreeWidth, parentCenter, parentIdx)
        }

        val rootBoxes = roots.map { buildBox(it, null) }

        // Compute absolute positions
        val layoutNodes = mutableListOf<LayoutNode>()
        val parentMap = mutableMapOf<Int, Int>()

        fun placeBoxes(box: SubtreeBox, depth: Int, leftX: Float) {
            val centerX = leftX + box.relativeCenterOffset
            val centerY = depth * (nodeHeight + vGap) + (nodeHeight / 2f)

            val rawState = nodeStates[box.node.index]
            val hasChildren = box.children.isNotEmpty()
            val effectiveStatus = rawState?.state ?: if (hasChildren) aggregatedStatus[box.node.index] ?: "PENDING" else "PENDING"

            val lNode = LayoutNode(
                node = box.node,
                depth = depth,
                x = centerX,
                y = centerY,
                width = nodeWidth,
                height = nodeHeight,
                status = effectiveStatus,
                stateObj = rawState,
                parentIndex = box.parentIndex,
                childrenIndices = box.children.map { it.node.index },
            )
            layoutNodes.add(lNode)
            if (box.parentIndex != null) {
                parentMap[box.node.index] = box.parentIndex!!
            }

            // Position children
            val totalChildrenWidth = box.children.sumOf { it.width.toDouble() }.toFloat() +
                (box.children.size - 1) * hGap

            val childrenSpanStart = leftX + if (totalChildrenWidth < box.width) {
                (box.width - totalChildrenWidth) / 2f
            } else 0f

            var childLeft = childrenSpanStart
            box.children.forEach { cBox ->
                placeBoxes(cBox, depth + 1, childLeft)
                childLeft += cBox.width + hGap
            }
        }

        var rootLeft = 0f
        rootBoxes.forEach { rBox ->
            placeBoxes(rBox, 0, rootLeft)
            rootLeft += rBox.width + hGap * 2
        }

        val nodesByIndex = layoutNodes.associateBy { it.node.index }

        // Identify Active Path (from roots down to running nodes)
        val runningNodes = layoutNodes.filter { it.status.uppercase() in listOf("RUNNING", "ACTIVE", "VERIFYING") }
        val activePathEdges = mutableSetOf<Pair<Int, Int>>() // Pair(parentIndex, childIndex)
        val activeNodeIndices = mutableSetOf<Int>()

        runningNodes.forEach { runningNode ->
            var curr: LayoutNode? = runningNode
            while (curr != null) {
                activeNodeIndices.add(curr.node.index)
                val pIdx = curr.parentIndex
                if (pIdx != null) {
                    activePathEdges.add(Pair(pIdx, curr.node.index))
                    curr = nodesByIndex[pIdx]
                } else {
                    curr = null
                }
            }
        }

        // Construct Edges
        val edges = mutableListOf<LayoutEdge>()
        layoutNodes.forEach { parent ->
            parent.childrenIndices.forEach { childIdx ->
                val child = nodesByIndex[childIdx]
                if (child != null) {
                    val isActive = Pair(parent.node.index, childIdx) in activePathEdges
                    edges.add(LayoutEdge(parent, child, isActive))
                }
            }
        }

        // Compute Bounding Rect
        val minX = layoutNodes.minOfOrNull { it.left } ?: 0f
        val minY = layoutNodes.minOfOrNull { it.top } ?: 0f
        val maxX = layoutNodes.maxOfOrNull { it.right } ?: 0f
        val maxY = layoutNodes.maxOfOrNull { it.bottom } ?: 0f

        val bounds = Rect(minX, minY, maxX, maxY)

        return TreeLayoutResult(
            nodes = layoutNodes,
            edges = edges,
            bounds = bounds,
            activeNodeIndices = activeNodeIndices,
        )
    }
}
