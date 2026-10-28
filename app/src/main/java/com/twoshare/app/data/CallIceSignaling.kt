package com.twoshare.app.data

import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CallIceCandidateRow(
    val id: String,
    @SerialName("call_id") val callId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("sdp_mid") val sdpMid: String? = null,
    @SerialName("sdp_mline_index") val sdpMLineIndex: Int,
    val candidate: String,
    @SerialName("created_at") val createdAt: String
)

@Serializable
private data class CallIceCandidateInsert(
    @SerialName("call_id") val callId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("sdp_mid") val sdpMid: String? = null,
    @SerialName("sdp_mline_index") val sdpMLineIndex: Int,
    val candidate: String
)

suspend fun TwoShareRepository.publishIceCandidate(
    callId: String,
    sdpMid: String?,
    sdpMLineIndex: Int,
    candidate: String
) {
    val uid = currentUserId() ?: error("Not signed in")
    requireAllowedUser()
    SupabaseProvider.client.from("call_ice_candidates").insert(
        CallIceCandidateInsert(
            callId = callId,
            userId = uid,
            sdpMid = sdpMid,
            sdpMLineIndex = sdpMLineIndex,
            candidate = candidate
        )
    )
}

suspend fun TwoShareRepository.loadRemoteIceCandidates(callId: String): List<CallIceCandidateRow> {
    val uid = currentUserId() ?: return emptyList()
    requireAllowedUser()
    return SupabaseProvider.client.from("call_ice_candidates")
        .select {
            filter { eq("call_id", callId) }
            order("created_at", Order.ASCENDING)
        }
        .decodeList<CallIceCandidateRow>()
        .filter { it.userId != uid }
}
