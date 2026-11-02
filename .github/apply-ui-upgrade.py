from pathlib import Path

# Keep this workflow idempotent: only apply missing UI enhancements.
p=Path('app/src/main/AndroidManifest.xml'); s=p.read_text()
s=s.replace(' android:windowSoftInputMode="adjustResize" android:windowSoftInputMode="adjustResize"',' android:windowSoftInputMode="adjustResize"')
p.write_text(s)

p=Path('app/src/main/java/com/twoshare/app/ui/MessengerScreenV2.kt'); s=p.read_text()
# Persistent local chat theme selection.
old='var messages by remember{mutableStateOf(OfflineCache.loadMessages().takeLast(MESSAGE_PAGE))};'
new='val chatPrefs=remember{context.getSharedPreferences("twoshare_chat",Context.MODE_PRIVATE)};var chatTheme by remember{mutableIntStateOf(chatPrefs.getInt("theme",0))};var themePicker by remember{mutableStateOf(false)};'+old
if 'chatPrefs=remember' not in s: s=s.replace(old,new,1)
# Chat background and day separators.
old='LazyColumn(state=listState,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){itemsIndexed(timeline,key={_,entry->'
new='val chatBackground=when(chatTheme){1->MaterialTheme.colorScheme.primaryContainer.copy(alpha=.18f);2->MaterialTheme.colorScheme.tertiaryContainer.copy(alpha=.20f);3->MaterialTheme.colorScheme.secondaryContainer.copy(alpha=.20f);else->MaterialTheme.colorScheme.surfaceContainerLowest};LazyColumn(state=listState,modifier=Modifier.fillMaxSize().background(chatBackground),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){itemsIndexed(timeline,key={_,entry->'
if 'val chatBackground=when(chatTheme)' not in s: s=s.replace(old,new,1)
old='){_,entry->val item=entry.second;if(item is UiChatMessage){'
new='){index,entry->val item=entry.second;val previous=timeline.getOrNull(index-1);if(previous==null||dayKey(previous.first)!=dayKey(entry.first))DateDivider(entry.first);if(item is UiChatMessage){'
if 'dayKey(previous.first)' not in s: s=s.replace(old,new,1)
# Theme dialog after Scaffold.
marker='\n}\n@Composable private fun UnreadDivider()'
dialog='\n if(themePicker)AlertDialog(onDismissRequest={themePicker=false},title={Text("Chat theme")},text={Column{listOf("Default","Blush","Lavender","Ocean").forEachIndexed{i,label->TextButton(onClick={chatTheme=i;chatPrefs.edit().putInt("theme",i).apply();themePicker=false},modifier=Modifier.fillMaxWidth()){Text((if(chatTheme==i)"✓ " else "")+label)}}}},confirmButton={TextButton(onClick={themePicker=false}){Text("Close")}})\n}\n@Composable private fun UnreadDivider()'
if '@Composable private fun DateDivider' not in s and marker in s:
    s=s.replace(marker,dialog,1)
    insert='''\nprivate fun dayKey(ms:Long):LocalDate=Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()\nprivate fun dayLabel(ms:Long):String{val d=dayKey(ms);val today=LocalDate.now();return when(d){today->"Today";today.minusDays(1)->"Yesterday";else->d.format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy"))}}\n@Composable private fun DateDivider(ms:Long){Row(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){HorizontalDivider(Modifier.weight(1f));Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh){Text(dayLabel(ms),Modifier.padding(horizontal=12.dp,vertical=5.dp),style=MaterialTheme.typography.labelMedium)};HorizontalDivider(Modifier.weight(1f))}}\n'''
    s=s.replace('@Composable private fun UnreadDivider()',insert+'@Composable private fun UnreadDivider()',1)

# Compact Messenger header: keep calls visible, move secondary actions under overflow.
if 'var headerMenu by remember' not in s:
    anchor='var initialScrollDone by remember{mutableStateOf(false)}'
    s=s.replace(anchor,anchor+';var headerMenu by remember{mutableStateOf(false)};var heartBurst by remember{mutableIntStateOf(0)}',1)
old='actions={FilledTonalIconButton(onClick={themePicker=true}){Icon(Icons.Default.Palette,"Chat theme")};Spacer(Modifier.width(5.dp));FilledTonalIconButton(onClick={draft="❤️ Thinking of you";send()}){Icon(Icons.Default.Favorite,"Thinking of you")};Spacer(Modifier.width(5.dp));FilledTonalIconButton(onClick={scope.launch{runCatching{repository.createOutgoingCall(false)}.onSuccess{onStartCall(it,false)}}}){Icon(Icons.Default.Call,"Voice call")};Spacer(Modifier.width(5.dp));FilledTonalIconButton(onClick={scope.launch{runCatching{repository.createOutgoingCall(true)}.onSuccess{onStartCall(it,false)}}}){Icon(Icons.Default.VideoCall,"Video call")};Spacer(Modifier.width(8.dp))}'
new='actions={FilledTonalIconButton(onClick={scope.launch{runCatching{repository.createOutgoingCall(false)}.onSuccess{onStartCall(it,false)}}}){Icon(Icons.Default.Call,"Voice call")};Spacer(Modifier.width(4.dp));FilledTonalIconButton(onClick={scope.launch{runCatching{repository.createOutgoingCall(true)}.onSuccess{onStartCall(it,false)}}}){Icon(Icons.Default.VideoCall,"Video call")};Box{IconButton(onClick={headerMenu=true}){Icon(Icons.Default.MoreVert,"More")};DropdownMenu(expanded=headerMenu,onDismissRequest={headerMenu=false}){DropdownMenuItem(text={Text("Thinking of you")},leadingIcon={Icon(Icons.Default.Favorite,null)},onClick={headerMenu=false;heartBurst++;draft="❤️ Thinking of you";send()});DropdownMenuItem(text={Text("Chat theme")},leadingIcon={Icon(Icons.Default.Palette,null)},onClick={headerMenu=false;themePicker=true})}};Spacer(Modifier.width(4.dp))}'
if old in s: s=s.replace(old,new,1)
# Floating heart animation overlay in the chat area.
old='Column(Modifier.fillMaxSize().padding(padding)){error?.let{Text(it,Modifier.padding(10.dp),color=MaterialTheme.colorScheme.error)};'
new='Box(Modifier.fillMaxSize().padding(padding)){Column(Modifier.fillMaxSize()){error?.let{Text(it,Modifier.padding(10.dp),color=MaterialTheme.colorScheme.error)};'
if old in s and 'heartBurst>0' not in s:
    s=s.replace(old,new,1)
    end='}}}}\n if(themePicker)AlertDialog'
    repl='}}};if(heartBurst>0){key(heartBurst){FloatingThinkingHeart()}}}\n if(themePicker)AlertDialog'
    s=s.replace(end,repl,1)
if '@Composable private fun FloatingThinkingHeart' not in s:
    marker='\nprivate fun dayKey(ms:Long):LocalDate='
    helper='''\n@Composable private fun FloatingThinkingHeart(){var go by remember{mutableStateOf(false)};LaunchedEffect(Unit){go=true};val rise by androidx.compose.animation.core.animateDpAsState(if(go)(-150).dp else 0.dp,animationSpec=androidx.compose.animation.core.tween(900),label="heartRise");val alpha by animateFloatAsState(if(go)0f else 1f,animationSpec=androidx.compose.animation.core.tween(900),label="heartFade");val scale by animateFloatAsState(if(go)1.65f else .75f,animationSpec=androidx.compose.animation.core.tween(420),label="heartScale");Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("❤️",fontSize=46.sp,modifier=Modifier.offset(y=rise).graphicsLayer{this.alpha=alpha;scaleX=scale;scaleY=scale})}}\n'''
    s=s.replace(marker,helper+marker,1)
# Imports required by the floating animation.
if 'import androidx.compose.ui.graphics.graphicsLayer' not in s:
    s=s.replace('import androidx.compose.ui.graphics.Color','import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.graphicsLayer',1)
p.write_text(s)
