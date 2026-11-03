from pathlib import Path

repo = Path('app/src/main/java/com/twoshare/app/data/TwoShareRepository.kt')
s = repo.read_text()

old = '''        val profiles = loadProfiles()
        val likes = retryNetworkRead {'''
new = '''        val profiles = loadProfiles()
        // Load Now Together attribution once with the feed. Do not start a database
        // request from every visible post while LazyColumn is scrolling.
        val nowTogetherByPost = retryNetworkRead {
            supabase.from("now_together_sessions").select {
                filter { eq("status", "completed") }
            }.decodeList<NowTogetherSessionRow>()
        }.mapNotNull { session -> session.finalPostId?.let { it to session } }.toMap()
        val likes = retryNetworkRead {'''
if old not in s:
    raise SystemExit('loadFeed profiles anchor not found')
s = s.replace(old, new, 1)

old = '''                createdAt = post.createdAt,
                likedBy = likes[post.id].orEmpty().map { it.userId }.toSet()
            )'''
new = '''                createdAt = post.createdAt,
                likedBy = likes[post.id].orEmpty().map { it.userId }.toSet(),
                nowTogetherPartnerName = nowTogetherByPost[post.id]?.let { session -> profiles[session.partnerId]?.displayName ?: "Partner" },
                nowTogetherInitiatorCaption = nowTogetherByPost[post.id]?.initiatorCaption,
                nowTogetherPartnerCaption = nowTogetherByPost[post.id]?.partnerCaption
            )'''
if old not in s:
    raise SystemExit('FeedPost mapping anchor not found')
s = s.replace(old, new, 1)
repo.write_text(s)

caption = Path('app/src/main/java/com/twoshare/app/ui/NowTogetherCaption.kt')
caption.write_text('''package com.twoshare.app.ui

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
    if (!post.isNowTogether) {
        post.caption?.takeIf { it.isNotBlank() }?.let {
            Text(it, Modifier.padding(horizontal = 14.dp, vertical = 11.dp), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
        Text("Now Together ❤️", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
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
''')

# Render the preloaded Now Together attribution synchronously. This restores both
# participant names/captions without doing a database request per visible feed item.
feed = Path('app/src/main/java/com/twoshare/app/ui/FeedScreen.kt')
f = feed.read_text()
old = '''post.caption?.takeIf{it.isNotBlank()}?.let{Text(it,Modifier.padding(horizontal=14.dp,vertical=11.dp),style=MaterialTheme.typography.bodyMedium)}'''
if old not in f:
    raise SystemExit('Feed caption render anchor not found')
f = f.replace(old, '''FeedPostCaption(post,repository)''', 1)
feed.write_text(f)
