package com.robonix.client.data.local

import com.robonix.client.data.model.*
import com.robonix.client.ui.chat.PlanRecord
import org.json.JSONArray
import org.json.JSONObject

/** Local history format, independent of the wire protocol. Bad entries are skipped individually. */
internal object PlanHistoryCodec {
    private fun obj(vararg fields: Pair<String, Any?>) = JSONObject().apply {
        fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }
    private fun array(values: Iterable<*>) = JSONArray().apply { values.forEach { put(it) } }
    private fun jsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply { value.forEach { (k, v) -> put(k.toString(), jsonValue(v)) } }
        is Iterable<*> -> array(value.map(::jsonValue))
        else -> value
    }
    private fun value(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { value(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { value(value.get(it)) }
        else -> value
    }
    private fun <T> entries(json: String, decode: (JSONObject) -> T): List<T> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i -> runCatching { decode(arr.getJSONObject(i)) }.getOrNull() }
    }.getOrDefault(emptyList())

    fun encodePlan(plan: RtdlPlan): String = planObject(plan).toString()
    fun decodePlan(json: String?): RtdlPlan? = json?.let { runCatching { plan(JSONObject(it)) }.getOrNull() }
    private fun planObject(p: RtdlPlan) = obj(
        "planId" to p.planId, "sessionId" to p.sessionId, "round" to p.round,
        "rootIndex" to p.rootIndex, "nodes" to array(p.nodes.map { n -> obj(
            "index" to n.index, "kindId" to n.kindId, "kind" to n.kind,
            "children" to array(n.children), "opId" to n.opId, "description" to n.description,
            "call" to n.call?.let { c -> obj("callId" to c.callId, "providerId" to c.providerId,
                "contractId" to c.contractId, "name" to c.name, "args" to jsonValue(c.args)) },
        ) }),
    )
    private fun plan(p: JSONObject): RtdlPlan {
        val nodes = p.getJSONArray("nodes")
        return RtdlPlan(p.getString("planId"), p.optString("sessionId"), p.getInt("round"), p.getInt("rootIndex"),
            (0 until nodes.length()).map { i ->
                val n = nodes.getJSONObject(i)
                val children = n.optJSONArray("children") ?: JSONArray()
                RtdlNode(n.getInt("index"), n.optInt("kindId"), n.optString("kind"),
                    (0 until children.length()).map { children.getInt(it) }, n.optString("opId"), n.optString("description"),
                    n.optJSONObject("call")?.let { c ->
                        val args = c.optJSONObject("args") ?: JSONObject()
                        CapabilityCall(c.optString("callId"), c.optString("providerId"), c.optString("contractId"),
                            c.optString("name"), args.keys().asSequence().associateWith { value(args.get(it)) })
                    })
            })
    }
    private fun stateObject(s: RtdlNodeState) = obj("nodeIndex" to s.nodeIndex, "state" to s.state,
        "planId" to s.planId, "opId" to s.opId, "description" to s.description,
        "leafResult" to s.leafResult?.let { r -> obj("callId" to r.callId, "providerId" to r.providerId,
            "contractId" to r.contractId, "name" to r.name, "success" to r.success,
            "output" to r.output, "error" to r.error) })
    private fun state(s: JSONObject) = RtdlNodeState(s.getInt("nodeIndex"), s.getString("state"),
        s.optJSONObject("leafResult")?.let { r -> CapabilityCallResult(r.optString("callId"),
            r.optString("providerId"), r.optString("contractId"), r.optString("name"),
            r.optBoolean("success"), r.optString("output"), r.optString("error")) },
        s.optString("planId"), s.optString("opId"), s.optString("description"))
    fun encodeStates(states: Map<Int, RtdlNodeState>): String = JSONObject().apply {
        states.forEach { (key, s) -> put(key.toString(), stateObject(s)) }
    }.toString()
    fun decodeStates(json: String): Map<Int, RtdlNodeState> = runCatching {
        val o = JSONObject(json)
        o.keys().asSequence().mapNotNull { key -> runCatching {
            val index = key.toInt()
            val s = state(o.getJSONObject(key))
            require(index == s.nodeIndex)
            index to s
        }.getOrNull() }.toMap()
    }.getOrDefault(emptyMap())
    private fun batchObject(b: BatchResult) = obj("planId" to b.planId, "sessionId" to b.sessionId,
        "round" to b.round, "anyFailed" to b.anyFailed, "results" to array(b.results.map(::stateObject)))
    fun encodeBatches(batches: List<BatchResult>): String = array(batches.map(::batchObject)).toString()
    fun decodeBatches(json: String): List<BatchResult> = entries(json) { b ->
        BatchResult(b.getString("planId"), b.optString("sessionId"), b.getInt("round"), b.optBoolean("anyFailed"),
            entries(b.optJSONArray("results")?.toString() ?: "[]", ::state))
    }
    fun encodeRecords(records: List<PlanRecord>): String = array(records.map { r -> obj(
        "key" to r.key, "plan" to planObject(r.plan), "updatedAt" to r.updatedAt,
        "nodeStates" to JSONObject(encodeStates(r.nodeStates)), "batches" to JSONArray(encodeBatches(r.batches)),
    ) }).toString()
    fun decodeRecords(json: String): List<PlanRecord> = entries(json) { r ->
        val p = plan(r.getJSONObject("plan"))
        PlanRecord("${p.planId}:${p.round}", p, decodeStates(r.optString("nodeStates", "{}")),
            decodeBatches(r.optString("batches", "[]")), r.optLong("updatedAt", 0))
    }
}
