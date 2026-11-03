package com.twoshare.app.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NowTogetherSessionRow(
    val id: String,
    @SerialName("initiator_id") val initiatorId: String,
    @SerialName("partner_id") val partnerId: String,
    @SerialName("initiator_photo_path") val initiatorPhotoPath: String? = null,
    @SerialName("partner_photo_path") val partnerPhotoPath: String? = null,
    @SerialName("initiator_caption") val initiatorCaption: String? = null,
    @SerialName("partner_caption") val partnerCaption: String? = null,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("partner_opened_at") val partnerOpenedAt: String? = null,
    @SerialName("grace_expires_at") val graceExpiresAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("final_post_id") val finalPostId: String? = null,
    @SerialName("initiator_captured_at") val initiatorCapturedAt: String? = null,
    @SerialName("partner_captured_at") val partnerCapturedAt: String? = null
)

@Serializable
data class NowTogetherSessionInsert(
    @SerialName("initiator_id") val initiatorId: String,
    @SerialName("partner_id") val partnerId: String,
    @SerialName("initiator_photo_path") val initiatorPhotoPath: String,
    @SerialName("initiator_caption") val initiatorCaption: String? = null,
    @SerialName("initiator_captured_at") val initiatorCapturedAt: String
)
