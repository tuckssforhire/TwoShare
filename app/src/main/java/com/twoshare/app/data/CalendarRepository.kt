package com.twoshare.app.data

import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CalendarEvent(
    val id: String,
    @SerialName("created_by") val createdBy: String,
    val title: String,
    val notes: String? = null,
    val category: String = "Other",
    @SerialName("start_at") val startAt: String,
    @SerialName("end_at") val endAt: String,
    @SerialName("all_day") val allDay: Boolean = false,
    @SerialName("repeat_rule") val repeatRule: String = "none",
    @SerialName("reminder_minutes") val reminderMinutes: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
private data class CalendarEventInsert(
    @SerialName("created_by") val createdBy: String,
    val title: String,
    val notes: String? = null,
    val category: String,
    @SerialName("start_at") val startAt: String,
    @SerialName("end_at") val endAt: String,
    @SerialName("all_day") val allDay: Boolean,
    @SerialName("repeat_rule") val repeatRule: String,
    @SerialName("reminder_minutes") val reminderMinutes: Int? = null
)

class CalendarRepository(private val base: TwoShareRepository) {
    private val supabase = SupabaseProvider.client

    suspend fun loadEvents(): List<CalendarEvent> {
        base.requireAllowedUser()
        return base.retryNetworkRead {
            supabase.from("calendar_events").select {
                order("start_at", Order.ASCENDING)
            }.decodeList<CalendarEvent>()
        }
    }

    suspend fun createEvent(title:String, notes:String?, category:String, startAt:String, endAt:String, allDay:Boolean, repeatRule:String, reminderMinutes:Int?) {
        val uid=base.currentUserId() ?: error("Your session has expired. Please sign in again.")
        val cleanTitle=title.trim(); require(cleanTitle.isNotEmpty() && cleanTitle.length<=120)
        require(category in categories); require(repeatRule in repeatRules)
        supabase.from("calendar_events").insert(CalendarEventInsert(uid,cleanTitle,notes?.trim()?.takeIf{it.isNotEmpty()},category,startAt,endAt,allDay,repeatRule,reminderMinutes))
    }

    suspend fun updateEvent(event:CalendarEvent, title:String, notes:String?, category:String, startAt:String, endAt:String, allDay:Boolean, repeatRule:String, reminderMinutes:Int?) {
        base.requireAllowedUser(); val cleanTitle=title.trim(); require(cleanTitle.isNotEmpty() && cleanTitle.length<=120)
        require(category in categories); require(repeatRule in repeatRules)
        supabase.from("calendar_events").update({
            set("title",cleanTitle);set("notes",notes?.trim()?.takeIf{it.isNotEmpty()});set("category",category);set("start_at",startAt);set("end_at",endAt);set("all_day",allDay);set("repeat_rule",repeatRule);set("reminder_minutes",reminderMinutes);set("updated_at",java.time.Instant.now().toString())
        }) { filter { eq("id",event.id) } }
    }

    suspend fun deleteEvent(event:CalendarEvent) {
        base.requireAllowedUser()
        supabase.from("calendar_events").delete { filter { eq("id",event.id) } }
    }

    companion object {
        val categories=listOf("Family","Work","Date","Birthday","Appointment","Reminder","Other")
        val repeatRules=listOf("none","daily","weekly","monthly","yearly")
    }
}
