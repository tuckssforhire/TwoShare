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
# Add palette button before Thinking of You.
old='actions={FilledTonalIconButton(onClick={draft="❤️ Thinking of you";send()})'
new='actions={FilledTonalIconButton(onClick={themePicker=true}){Icon(Icons.Default.Palette,"Chat theme")};Spacer(Modifier.width(5.dp));FilledTonalIconButton(onClick={draft="❤️ Thinking of you";send()})'
if 'Icon(Icons.Default.Palette,"Chat theme")' not in s: s=s.replace(old,new,1)
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
p.write_text(s)
