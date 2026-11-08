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
    val width: Float = 240f,
    val height: Float = 50f,
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
    const val DEFAULT_NODE_WIDTH = 240f
    const val DEFAULT_NODE_HEIGHT = 50f
    const val DEFAULT_H_GAP = 28f
    const val DEFAULT_V_GAP = 32f

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
                        childStatuses.any { it in listOf("FAILED", "ERROR", "TIMEOUT", "CANCELED", "CANCELLED", "ABORTED") } -> "FAILED"
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
     * Computes 2D coordinates and edges for the behavior tree:
     * - Sequence, Selector, and Fallback nodes are laid out strictly SERIAL (串行, vertically).
     * - Parallel nodes are laid out in PARALLEL (并列, horizontally).
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

        // Subtree bounding and layout structure
        class SubtreeBox(
            val node: RtdlNode,
            val children: List<SubtreeBox>,
            val isParallel: Boolean,
            var width: Float,
            var height: Float,
            var relativeCenterX: Float,
            var relativeCenterY: Float,
            var parentIndex: Int? = null,
        )

        val visited = mutableSetOf<Int>()

        fun buildBox(node: RtdlNode, parentIdx: Int?): SubtreeBox {
            visited.add(node.index)
            val validChildren = node.children.mapNotNull { byIndex[it] }.filter { it.index !in visited }
            val isParallel = node.kind.equals("parallel", ignoreCase = true) ||
                node.kind.equals("concurrent", ignoreCase = true)

            val childBoxes = validChildren.map { buildBox(it, node.index) }

            if (childBoxes.isEmpty()) {
                return SubtreeBox(
                    node = node,
                    children = emptyList(),
                    isParallel = isParallel,
                    width = nodeWidth,
                    height = nodeHeight,
                    relativeCenterX = nodeWidth / 2f,
                    relativeCenterY = nodeHeight / 2f,
                    parentIndex = parentIdx,
                )
            }

            if (isParallel) {
                // Parallel execution: children placed horizontally side-by-side
                val totalChildrenWidth = childBoxes.sumOf { it.width.toDouble() }.toFloat() +
                    (childBoxes.size - 1) * hGap
                val maxChildrenHeight = childBoxes.maxOf { it.height }
                val subtreeWidth = max(nodeWidth, totalChildrenWidth)
                val subtreeHeight = nodeHeight + vGap + maxChildrenHeight

                return SubtreeBox(
                    node = node,
                    children = childBoxes,
                    isParallel = true,
                    width = subtreeWidth,
                    height = subtreeHeight,
                    relativeCenterX = subtreeWidth / 2f,
                    relativeCenterY = nodeHeight / 2f,
                    parentIndex = parentIdx,
                )
            } else {
                // Serial execution (Sequence / Selector / Fallback): children placed vertically in sequence
                val maxChildrenWidth = childBoxes.maxOf { it.width }
                val totalChildrenHeight = childBoxes.sumOf { it.height.toDouble() }.toFloat() +
                    (childBoxes.size - 1) * vGap
                val subtreeWidth = max(nodeWidth, maxChildrenWidth)
                val subtreeHeight = nodeHeight + vGap + totalChildrenHeight

                return SubtreeBox(
                    node = node,
                    children = childBoxes,
                    isParallel = false,
                    width = subtreeWidth,
                    height = subtreeHeight,
                    relativeCenterX = subtreeWidth / 2f,
                    relativeCenterY = nodeHeight / 2f,
                    parentIndex = parentIdx,
                )
            }
        }

        val rootBoxes = roots.map { buildBox(it, null) }

        // Compute absolute positions & construct edges
        val layoutNodes = mutableListOf<LayoutNode>()
        val rawEdges = mutableListOf<Triple<Int, Int, Boolean>>() // fromIndex, toIndex, isParentChild

        fun placeBoxes(box: SubtreeBox, leftX: Float, topY: Float, depth: Int) {
            val centerX = leftX + box.relativeCenterX
            val centerY = topY + box.relativeCenterY

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

            if (box.children.isEmpty()) return

            if (box.isParallel) {
                // Parallel: children laid out horizontally side-by-side
                val totalChildrenWidth = box.children.sumOf { it.width.toDouble() }.toFloat() +
                    (box.children.size - 1) * hGap
                val childrenSpanStart = leftX + (box.width - totalChildrenWidth) / 2f
                val childTop = topY + nodeHeight + vGap

                var childLeft = childrenSpanStart
                box.children.forEach { cBox ->
                    placeBoxes(cBox, childLeft, childTop, depth + 1)
                    rawEdges.add(Triple(box.node.index, cBox.node.index, true))
                    childLeft += cBox.width + hGap
                }
            } else {
                // Serial (Sequence / Selector): children laid out vertically in order
                var childTop = topY + nodeHeight + vGap
                var previousNodeIndex = box.node.index

                box.children.forEachIndexed { i, cBox ->
                    val childLeft = leftX + (box.width - cBox.width) / 2f
                    placeBoxes(cBox, childLeft, childTop, depth + 1 + i)

                    // Serial execution edge from previous node to this node
                    rawEdges.add(Triple(previousNodeIndex, cBox.node.index, i == 0))
                    previousNodeIndex = cBox.node.index

                    childTop += cBox.height + vGap
                }
            }
        }

        var rootLeft = 0f
        rootBoxes.forEach { rBox ->
            placeBoxes(rBox, rootLeft, 24f, 0)
            rootLeft += rBox.width + hGap * 2
        }

        val nodesByIndex = layoutNodes.associateBy { it.node.index }

        // Identify Active Path: edges leading into RUNNING / ACTIVE nodes
        val runningNodes = layoutNodes.filter { it.status.uppercase() in listOf("RUNNING", "ACTIVE", "VERIFYING") }
        val activeNodeIndices = mutableSetOf<Int>()

        runningNodes.forEach { runningNode ->
            var curr: LayoutNode? = runningNode
            while (curr != null) {
                activeNodeIndices.add(curr.node.index)
                val pIdx = curr.parentIndex
                curr = if (pIdx != null) nodesByIndex[pIdx] else null
            }
        }

        // Construct LayoutEdges
        val edges = rawEdges.mapNotNull { (fromIdx, toIdx, _) ->
            val fromNode = nodesByIndex[fromIdx]
            val toNode = nodesByIndex[toIdx]
            if (fromNode != null && toNode != null) {
                // Active if leading directly into an active/running node
                val isRunningTarget = toNode.status.uppercase() in listOf("RUNNING", "ACTIVE", "VERIFYING")
                val isRunningSource = fromNode.status.uppercase() in listOf("RUNNING", "ACTIVE", "VERIFYING")
                val isActive = isRunningTarget || (isRunningSource && toNode.status.uppercase() !in listOf("FAILED", "ERROR"))
                LayoutEdge(fromNode, toNode, isActive)
            } else null
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
