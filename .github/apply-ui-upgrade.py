from pathlib import Path

# Feed avatar stabilization. Previous UI migrations are already in source; keep this
# migration small and idempotent so the workflow cannot regress Messenger again.
p = Path('app/src/main/java/com/twoshare/app/ui/FeedScreen.kt')
s = p.read_text()

# Give feed cards a refresh token so profile-photo URLs are re-resolved periodically
# and after feed refreshes instead of being permanently remembered from first compose.
old = 'var showAccountSettings by remember{mutableStateOf(false)};var menuOpen by remember{mutableStateOf(false)}'
new = old + ';var avatarRefresh by remember{mutableLongStateOf(0L)}'
if 'var avatarRefresh by remember' not in s:
    s = s.replace(old, new, 1)

old = 'LaunchedEffect(Unit){while(isActive){runCatching{presence.heartbeat()};delay(30000)}}'
new = old + '\n LaunchedEffect(Unit){while(isActive){delay(60000);avatarRefresh++}}'
if 'delay(60000);avatarRefresh++' not in s:
    s = s.replace(old, new, 1)

old = 'PostCard(post,repository.currentUserId(),reactions[post.id].orEmpty(),reactionRepo.choices,'
new = 'PostCard(post,repository.currentUserId(),avatarRefresh,reactions[post.id].orEmpty(),reactionRepo.choices,'
if old in s:
    s = s.replace(old, new)

old = '@Composable private fun PostCard(post:FeedPost,currentUid:String?,reactions:Map<String,Set<String>>,choices:List<String>,'
new = '@Composable private fun PostCard(post:FeedPost,currentUid:String?,avatarRefresh:Long,reactions:Map<String,Set<String>>,choices:List<String>,'
if old in s:
    s = s.replace(old, new, 1)

old = 'val avatar by produceState<String?>(null,post.userId){value=ProfilePhotoStore.photoUrl(post.userId)};'
new = 'val avatar by produceState<String?>(null,post.userId,avatarRefresh){value=runCatching{ProfilePhotoStore.photoUrl(post.userId)}.getOrNull()};'
if old in s:
    s = s.replace(old, new, 1)

# Coil can fail while a signed/public URL is changing. Keep the avatar container and
# fall back to the poster initial instead of leaving a blank circle.
old = 'if(avatar!=null)AsyncImage(avatar,"${post.userName} profile photo",Modifier.fillMaxSize(),contentScale=ContentScale.Crop)else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(post.userName.take(1).uppercase(),fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)}'
new = 'if(avatar!=null)AsyncImage(model=avatar,contentDescription="${post.userName} profile photo",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop)else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(post.userName.take(1).uppercase().ifBlank{"?"},fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)}'
if old in s:
    s = s.replace(old, new, 1)

p.write_text(s)
