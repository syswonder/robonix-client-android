package com.robonix.client.data.grpc

import com.robonix.client.AppLog
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.ForwardingClientCallListener
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import java.util.concurrent.atomic.AtomicInteger

/**
 * Logs every gRPC call made through [GrpcChannelProvider] under tag "RPC":
 * method, target, status, duration and message counts, never payloads.
 * Streams log their open and close; unary calls go through [RpcLogPolicy].
 */
internal class RpcCallLogger(
    private val target: String,
    private val policy: RpcLogPolicy,
) : ClientInterceptor {
    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> {
        val key = "${method.fullMethodName.substringAfterLast('.')} @$target"
        val streaming = method.type != MethodDescriptor.MethodType.UNARY
        val sent = AtomicInteger()
        val received = AtomicInteger()
        return object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
            override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                val startedNs = System.nanoTime()
                if (streaming) AppLog.write(TAG, "open $key")
                val listener = object : ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
                    override fun onMessage(message: RespT) {
                        received.incrementAndGet()
                        super.onMessage(message)
                    }

                    override fun onClose(status: Status, trailers: Metadata) {
                        try {
                            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
                            val lines = if (streaming) {
                                listOf("close $key ${describe(status)} ${elapsedMs}ms sent=${sent.get()} recv=${received.get()}")
                            } else {
                                policy.unaryClosed(key, status.code.name, describe(status), elapsedMs, System.currentTimeMillis())
                            }
                            if (lines.isNotEmpty()) {
                                AppLog.recordRpcDiagnostic(method.fullMethodName, status.code, elapsedMs)
                                lines.forEach { AppLog.write(TAG, it) }
                            }
                        } catch (_: Exception) {}
                        super.onClose(status, trailers)
                    }
                }
                super.start(listener, headers)
            }

            override fun sendMessage(message: ReqT) {
                sent.incrementAndGet()
                super.sendMessage(message)
            }
        }
    }

    companion object {
        const val TAG = "RPC"

        fun describe(status: Status): String = buildString {
            append(status.code.name)
            status.description?.let { append(": ").append(it.take(300)) }
            status.cause?.let { append(" cause=").append(it.javaClass.simpleName).append(": ").append(it.message?.take(200)) }
        }
    }
}

/**
 * Decides which finished unary calls get a log line, so the 1-3 s polls don't
 * flood the file. Written: the first result per method@target, every change of
 * status code (a failure and the recovery after it both show), and calls slower
 * than [slowMs]. Repeats of the same result are only counted; every
 * [summaryEveryMs] one summary line per method@target reports those counts.
 */
internal class RpcLogPolicy(
    private val summaryEveryMs: Long = 60_000L,
    private val slowMs: Long = 2_000L,
) {
    private class Window {
        var ok = 0
        var fail = 0
        var totalMs = 0L
        var maxMs = 0L
    }

    private val lastCode = HashMap<String, String>()
    private val windows = LinkedHashMap<String, Window>()
    private var windowStartMs = -1L

    @Synchronized
    fun unaryClosed(key: String, code: String, detail: String, elapsedMs: Long, nowMs: Long): List<String> {
        val lines = ArrayList<String>(1)
        when (val previous = lastCode.put(key, code)) {
            null -> lines += "$key $detail ${elapsedMs}ms (first)"
            code -> if (elapsedMs >= slowMs) lines += "$key $detail ${elapsedMs}ms (slow)"
            else -> lines += "$key $detail ${elapsedMs}ms (was $previous)"
        }

        val w = windows.getOrPut(key) { Window() }
        if (code == "OK") w.ok++ else w.fail++
        w.totalMs += elapsedMs
        w.maxMs = maxOf(w.maxMs, elapsedMs)
        if (windowStartMs < 0) windowStartMs = nowMs
        val windowMs = nowMs - windowStartMs
        if (windowMs >= summaryEveryMs) {
            windows.forEach { (k, s) ->
                val last = lastCode[k]?.takeIf { it != "OK" }?.let { " last=$it" } ?: ""
                lines += "summary ${windowMs / 1000}s $k ok=${s.ok} fail=${s.fail} " +
                    "avg=${s.totalMs / (s.ok + s.fail)}ms max=${s.maxMs}ms$last"
            }
            windows.clear()
            windowStartMs = nowMs
        }
        return lines
    }
}
