package com.robonix.client.ui.chat.rtdl

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.robonix.client.ui.components.CodeBlockView
import com.robonix.client.ui.components.CyberCard
import com.robonix.client.ui.components.PulsingStatusDot
import com.robonix.client.ui.i18n.t
import com.robonix.client.ui.theme.*
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RtdlNodeDetailSheet(
    layoutNode: LayoutNode?,
    allNodesByIndex: Map<Int, LayoutNode>,
    onDismiss: () -> Unit,
    onSelectNode: (Int) -> Unit,
) {
    if (layoutNode == null) return

    val node = layoutNode.node
    val status = layoutNode.displayStatus
    val isRunning = status == RtdlDisplayStatus.Running
    val statusColor = when (status) {
        RtdlDisplayStatus.Running -> Amber
        RtdlDisplayStatus.Succeeded -> Green
        RtdlDisplayStatus.Failed -> Red
        RtdlDisplayStatus.Skipped, RtdlDisplayStatus.Pending -> Dim
    }

    val nodeIcon: ImageVector = when (node.kind.lowercase()) {
        "sequence" -> Icons.Default.FormatListNumbered
        "parallel" -> Icons.AutoMirrored.Filled.CallSplit
        "if" -> Icons.AutoMirrored.Filled.AltRoute
        "repeat" -> Icons.Default.Refresh
        "while" -> Icons.Default.Sync
        "fallback", "selector" -> Icons.AutoMirrored.Filled.AltRoute
        "condition" -> Icons.Default.CheckCircleOutline
        "action", "do" -> Icons.Default.PlayArrow
        else -> Icons.Default.Lens
    }

    val actionName = when {
        node.call != null -> node.call.name.substringAfterLast("/")
        node.kind.isNotBlank() && node.kind.lowercase() in listOf("sequence", "parallel", "selector", "fallback", "condition", "if", "repeat", "while", "do") ->
            node.kind.replaceFirstChar { it.uppercase() }
        node.description.isNotBlank() && !node.description.contains("-") && node.description.length <= 20 ->
            node.description
        node.opId.isNotBlank() && !node.opId.contains("-") ->
            node.opId.substringAfterLast("/")
        else -> node.kind.replaceFirstChar { it.uppercase() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Line),
            )
        },
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Header Row: Node Index, Icon, Name, and Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = if (isRunning) Amber.copy(alpha = 0.2f) else Cyan.copy(alpha = 0.15f),
                        shape = CircleShape,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = nodeIcon,
                                contentDescription = null,
                                tint = if (isRunning) Amber else Cyan,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }

                    Spacer(Modifier.width(10.dp))

                    Column {
                        Text(
                            text = "#${node.index} $actionName",
                            color = Text,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = t("rtdl.node.kind", node.kind.uppercase()),
                            color = Dim,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }

                // Status Tag
                Surface(
                    color = statusColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.5f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isRunning) {
                            PulsingStatusDot(color = Amber, size = 5.dp)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = t(status.label),
                            color = statusColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = LineSoft, thickness = 0.8.dp)
            Spacer(Modifier.height(12.dp))

            // Contract Info (Provider & Contract ID)
            if (node.call != null) {
                Text(
                    text = "${t("rtdl.node.provider")}: ${node.call.providerId}",
                    color = Muted,
                    fontSize = 11.5.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${t("rtdl.node.contract")}: ${node.call.contractId}",
                    color = Cyan,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(12.dp))
            } else if (node.description.isNotBlank()) {
                Text(
                    text = node.description,
                    color = Muted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(12.dp))
            }

            // Hierarchy Breadcrumbs (Parent & Children navigation)
            if (layoutNode.parentIndex != null || layoutNode.childrenIndices.isNotEmpty()) {
                Surface(
                    color = Panel2,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(0.8.dp, LineSoft),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        if (layoutNode.parentIndex != null) {
                            val parent = allNodesByIndex[layoutNode.parentIndex]
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = t("rtdl.nav.parent"),
                                    color = Dim,
                                    fontSize = 10.5.sp,
                                    modifier = Modifier.width(60.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                NavPill(
                                    label = "#${layoutNode.parentIndex} ${parent?.node?.call?.name ?: parent?.node?.kind ?: ""}",
                                    onClick = { onSelectNode(layoutNode.parentIndex) },
                                )
                            }
                        }

                        if (layoutNode.parentIndex != null && layoutNode.childrenIndices.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(color = LineSoft, thickness = 0.5.dp)
                            Spacer(Modifier.height(8.dp))
                        }

                        if (layoutNode.childrenIndices.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = t("rtdl.nav.children"),
                                    color = Dim,
                                    fontSize = 10.5.sp,
                                    modifier = Modifier.width(60.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    layoutNode.childrenIndices.forEachIndexed { i, cIdx ->
                                        val child = allNodesByIndex[cIdx]
                                        val roleSuffix = when (node.kind.lowercase()) {
                                            "if" -> if (i == 0) " (then)" else " (else)"
                                            "repeat", "while" -> " (body)"
                                            else -> ""
                                        }
                                        NavPill(
                                            label = "#$cIdx ${child?.node?.call?.name ?: child?.node?.kind ?: ""}$roleSuffix",
                                            onClick = { onSelectNode(cIdx) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            // Control Parameters: Condition, Bindings, Loop Limits
            if (node.bind.isNotBlank() || node.repeatCount > 0 || node.maxIterations > 0 || node.conditionJson.isNotBlank()) {
                Surface(
                    color = Panel2,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(0.8.dp, Amber.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            text = t("rtdl.control.title"),
                            color = Amber,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(6.dp))

                        if (node.bind.isNotBlank()) {
                            Text(
                                text = t("rtdl.control.bind", node.bind),
                                color = Text,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            Spacer(Modifier.height(4.dp))
                        }

                        if (node.repeatCount > 0) {
                            Text(
                                text = t("rtdl.control.repeat", node.repeatCount),
                                color = Text,
                                fontSize = 11.sp,
                            )
                            Spacer(Modifier.height(4.dp))
                        }

                        if (node.maxIterations > 0) {
                            Text(
                                text = t("rtdl.control.max_iterations", node.maxIterations),
                                color = Text,
                                fontSize = 11.sp,
                            )
                            Spacer(Modifier.height(4.dp))
                        }

                        if (node.conditionJson.isNotBlank()) {
                            Text(
                                text = t("rtdl.control.condition"),
                                color = Text,
                                fontSize = 11.sp,
                            )
                            Spacer(Modifier.height(2.dp))
                            val formattedCond = try {
                                JSONObject(node.conditionJson).toString(2)
                            } catch (_: Exception) {
                                node.conditionJson
                            }
                            CodeBlockView(code = formattedCond, language = "JSON")
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            // Control Node Execution Detail (operatorDetail)
            val opDetail = layoutNode.stateObj?.operatorDetail
            if (!opDetail.isNullOrBlank()) {
                Text(
                    text = t("rtdl.control.operator_detail"),
                    color = Cyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                CodeBlockView(code = opDetail, language = "LOG")
                Spacer(Modifier.height(14.dp))
            }

            // Call Arguments (Formatted JSON)
            if (node.call != null && node.call.args.isNotEmpty()) {
                Text(
                    text = t("rtdl.call.args"),
                    color = Text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                val truncatedArgs = t("rtdl.truncated.args")
                val argsJson = try {
                    val rawStr = node.call.args.toString()
                    if (rawStr.length > 20000) {
                        rawStr.take(8000) + "\n\n/* ... $truncatedArgs ... */"
                    } else {
                        JSONObject(node.call.args).toString(2)
                    }
                } catch (_: Exception) {
                    node.call.args.toString()
                }
                CodeBlockView(code = argsJson, language = "JSON")
                Spacer(Modifier.height(14.dp))
            }

            // Execution Results (LeafResult)
            val result = layoutNode.stateObj?.leafResult
            if (result != null) {
                Text(
                    text = t("rtdl.call.result", if (result.success) t("common.success") else t("common.failed")),
                    color = if (result.success) Green else Red,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))

                if (result.output.isNotBlank()) {
                    val truncatedOutput = t("rtdl.truncated.output", result.output.length / 1024)
                    val formattedOutput = try {
                        if (result.output.length > 20000) {
                            result.output.take(8000) + "\n\n/* ... $truncatedOutput ... */"
                        } else {
                            JSONObject(result.output).toString(2)
                        }
                    } catch (_: Exception) {
                        if (result.output.length > 20000) {
                            result.output.take(8000) + "\n\n/* ... $truncatedOutput ... */"
                        } else {
                            result.output
                        }
                    }
                    CodeBlockView(code = formattedOutput, language = "OUTPUT")
                }

                if (result.error.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    CyberCard(
                        modifier = Modifier.fillMaxWidth(),
                        borderColor = Red.copy(alpha = 0.5f),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                text = result.error,
                                color = Red,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavPill(label: String, onClick: () -> Unit) {
    Surface(
        color = Cyan.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(0.8.dp, Cyan.copy(alpha = 0.35f)),
        modifier = Modifier.clickable { onClick() },
    ) {
        Text(
            text = label.take(20),
            color = Cyan,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
