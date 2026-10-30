package com.twoshare.app.data

import com.twoshare.app.model.CallRow
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order

suspend fun TwoShareRepository.loadCallHistory(): List<CallRow> {
    requireAllowedUser()
    return SupabaseProvider.client
        .from("calls")
        .select {
            order("created_at", Order.ASCENDING)
        }
        .decodeList<CallRow>()
}
