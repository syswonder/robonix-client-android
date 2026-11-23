package com.robonix.client.domain

import com.robonix.client.AppLog
import com.robonix.client.data.grpc.AtlasClient
import com.robonix.client.data.grpc.GrpcChannelProvider
import com.robonix.client.data.model.RobotDescription
import com.robonix.proto.soma.GetUrdf_Request
import com.robonix.proto.soma.GetUrdf_Response
import com.robonix.proto.soma.GetYaml_Request
import com.robonix.proto.soma.GetYaml_Response
import com.robonix.proto.vitalsstream.StreamVitals_Request
import com.robonix.proto.vitalsstream.VitalsSnapshot
import io.grpc.CallOptions
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.protobuf.ProtoUtils
import io.grpc.stub.ClientCalls
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class RobotVitalsModel(
    val description: RobotDescription,
    val assets: Map<String, ByteArray>,
) {
    val hasUrdf: Boolean get() = description.urdfXml.isNotBlank()

    fun hasSameContent(other: RobotVitalsModel): Boolean =
        description == other.description && assets.keys == other.assets.keys &&
            assets.all { (path, bytes) -> bytes.contentEquals(other.assets.getValue(path)) }
}

/**
 * Soma robot description + Vitals hardware stream for the 3D health view.
 * Mirrors the web client's soma/get_yaml + soma/get_urdf + vitals/stream
 * contracts (see `src/robonix_client/vitals_transport.py`).
 */
@Singleton
class RobotVitalsRepository @Inject constructor(
    private val atlasClient: AtlasClient,
    private val channelProvider: GrpcChannelProvider,
) {
    companion object {
        const val CONTRACT_SOMA_GET_YAML = "robonix/system/soma/get_yaml"
        const val CONTRACT_SOMA_GET_URDF = "robonix/system/soma/get_urdf"
        const val CONTRACT_VITALS_STREAM = "robonix/system/vitals/stream"

        /** Virtual base URL the WebView intercepts to serve STL meshes from memory. */
        const val ASSET_BASE_URL = "https://robonix.local/assets/"
    }

    private val GET_YAML_METHOD: MethodDescriptor<GetYaml_Request, GetYaml_Response> =
        MethodDescriptor.newBuilder<GetYaml_Request, GetYaml_Response>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("robonix.contracts.RobonixSystemSomaGetYaml/GetYaml")
            .setRequestMarshaller(ProtoUtils.marshaller(GetYaml_Request.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(GetYaml_Response.getDefaultInstance()))
            .build()

    private val GET_URDF_METHOD: MethodDescriptor<GetUrdf_Request, GetUrdf_Response> =
        MethodDescriptor.newBuilder<GetUrdf_Request, GetUrdf_Response>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("robonix.contracts.RobonixSystemSomaGetUrdf/GetUrdf")
            .setRequestMarshaller(ProtoUtils.marshaller(GetUrdf_Request.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(GetUrdf_Response.getDefaultInstance()))
            .build()

    private val STREAM_VITALS_METHOD: MethodDescriptor<StreamVitals_Request, VitalsSnapshot> =
        MethodDescriptor.newBuilder<StreamVitals_Request, VitalsSnapshot>()
            .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
            .setFullMethodName("robonix.contracts.RobonixSystemVitalsStream/StreamVitals")
            .setRequestMarshaller(ProtoUtils.marshaller(StreamVitals_Request.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(VitalsSnapshot.getDefaultInstance()))
            .build()

    suspend fun loadRobotDescription(atlasEndpoint: String): RobotVitalsModel = withContext(Dispatchers.IO) {
        val yamlEndpoint = atlasClient.discoverEndpoint(atlasEndpoint, CONTRACT_SOMA_GET_YAML)
        val yamlResponse = ClientCalls.blockingUnaryCall(
            channelProvider.getChannel(yamlEndpoint).newCall(
                GET_YAML_METHOD, CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS),
            ),
            GetYaml_Request.getDefaultInstance(),
        )
        val yamlText = yamlResponse.yamlText
        val robotId = yamlResponse.robotId
        AppLog.write(
            "VITALS",
            "soma yaml from $yamlEndpoint robot=$robotId len=${yamlText.length} head=${yamlText.take(160)}\n" +
                "===YAML_BEGIN===\n$yamlText\n===YAML_END===",
        )

        var urdfXml = ""
        var assets = emptyMap<String, ByteArray>()
        try {
            val urdfEndpoint = atlasClient.discoverEndpoint(atlasEndpoint, CONTRACT_SOMA_GET_URDF)
            val urdfResponse = ClientCalls.blockingUnaryCall(
                channelProvider.getChannel(urdfEndpoint).newCall(
                    GET_URDF_METHOD, CallOptions.DEFAULT.withDeadlineAfter(15, TimeUnit.SECONDS),
                ),
                GetUrdf_Request.newBuilder().setRobotId(robotId).setIncludeAssets(true).build(),
            )
            urdfXml = urdfResponse.urdfXml
            assets = urdfResponse.assetsList.associate { it.path to it.data.toByteArray() }
            AppLog.write("VITALS", "urdf from $urdfEndpoint assets=${assets.size}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.write("VITALS", "GetUrdf failed; procedural fallback", e)
        }

        val description = try {
            RobotVitalsMapper.normalizeRobotDescription(yamlText, urdfXml, robotId, ASSET_BASE_URL)
        } catch (e: Exception) {
            // Surface the real YAML load problem: the UI sees the generic
            // "Soma YAML must contain a mapping" but the log file keeps the
            // cause + the raw YAML dump above.
            AppLog.write("VITALS", "normalizeRobotDescription failed len=${yamlText.length}", e)
            throw e
        }
        RobotVitalsModel(description, assets)
    }

    fun streamVitalsSnapshots(atlasEndpoint: String): Flow<VitalsSnapshot> = callbackFlow {
        val endpoint = atlasClient.discoverEndpoint(atlasEndpoint, CONTRACT_VITALS_STREAM)
        AppLog.write("VITALS", "vitals stream endpoint=$endpoint")
        val channel = channelProvider.getChannel(endpoint)
        val call = channel.newCall(STREAM_VITALS_METHOD, CallOptions.DEFAULT)
        // Async server stream: the gRPC call is owned here and torn down on
        // collector cancellation via awaitClose, so leaving the Vitals screen
        // (or switching robot host) actually closes the stream instead of
        // leaving it to leak on an IO thread. Mirrors LiaisonClient's streams.
        ClientCalls.asyncServerStreamingCall(
            call,
            StreamVitals_Request.getDefaultInstance(),
            object : StreamObserver<VitalsSnapshot> {
                override fun onNext(value: VitalsSnapshot) {
                    trySend(value)
                }

                override fun onError(t: Throwable) {
                    // Leaving the screen cancels the call and lands here too; the
                    // RPC close line already records that, so skip the stack trace.
                    if (Status.fromThrowable(t).code != Status.Code.CANCELLED) {
                        AppLog.write("VITALS", "vitals stream error: ${t.message}", t)
                    }
                    close(t)
                }

                override fun onCompleted() {
                    close()
                }
            },
        )
        awaitClose { call.cancel("vitals stream cancelled", null) }
    }.flowOn(Dispatchers.IO)
}
