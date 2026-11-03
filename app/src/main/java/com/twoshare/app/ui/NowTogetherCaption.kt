package com.twoshare.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.twoshare.app.data.TwoShareRepository
import com.twoshare.app.model.FeedPost

@Composable
fun FeedPostCaption(post: FeedPost, repository: TwoShareRepository) {
    // Never perform a Supabase request from an individual LazyColumn item. During
    // fast scrolling Compose creates/disposes several items in quick succession;
    // the old produceState implementation could launch a request for every post.
    if (!post.isNowTogether) {
        post.caption?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }

    Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
        Text(
            "Now Together ❤️",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        post.nowTogetherInitiatorCaption?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(7.dp))
            Text(post.userName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        post.nowTogetherPartnerCaption?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(7.dp))
            Text(post.nowTogetherPartnerName ?: "Partner", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
