from pathlib import Path

main = Path('app/src/main/java/com/twoshare/app/MainActivity.kt')
s = main.read_text()
old = 'else if(showCalendar)CalendarScreen(repository){showCalendar=false}else if(showMessenger)MessengerScreenV2(repository,{showMessenger=false}){startedCall,incoming->incomingCall=null;activeCallIncoming=incoming;activeCall=startedCall}else FeedScreen(repository,authManager,selectedTheme,::chooseTheme,{showMessenger=true;showCalendar=false},{showCalendar=true;showMessenger=false}){scope.launch'
new = '''else Scaffold(bottomBar={TwoShareBottomNavigation(selected=when{showCalendar->TwoShareTab.Calendar;showMessenger->TwoShareTab.Messenger;else->TwoShareTab.Feed}){tab->showMessenger=tab==TwoShareTab.Messenger;showCalendar=tab==TwoShareTab.Calendar}}){navPadding->Box(Modifier.fillMaxSize().padding(navPadding)){when{showCalendar->CalendarScreen(repository){showCalendar=false};showMessenger->MessengerScreenV2(repository,{showMessenger=false}){startedCall,incoming->incomingCall=null;activeCallIncoming=incoming;activeCall=startedCall};else->FeedScreen(repository,authManager,selectedTheme,::chooseTheme,{showMessenger=true;showCalendar=false},{showCalendar=true;showMessenger=false}){scope.launch'''
if old not in s:
    raise SystemExit('MainActivity navigation anchor not found')
s = s.replace(old, new, 1)
old2 = 'biometricLockEnabled=false;biometricRequired=false;signedIn=false}};else->LoginScreen'
new2 = 'biometricLockEnabled=false;biometricRequired=false;signedIn=false}}}}};else->LoginScreen'
if old2 not in s:
    raise SystemExit('MainActivity navigation close anchor not found')
s = s.replace(old2, new2, 1)
main.write_text(s)

feed = Path('app/src/main/java/com/twoshare/app/ui/FeedScreen.kt')
s = feed.read_text()
s = s.replace('import androidx.compose.material.icons.filled.AddPhotoAlternate\n', 'import androidx.compose.material.icons.filled.Add\nimport androidx.compose.material.icons.filled.AddPhotoAlternate\n')
s = s.replace('var menuOpen by remember{mutableStateOf(false)};', 'var menuOpen by remember{mutableStateOf(false)};var addMenuOpen by remember{mutableStateOf(false)};')
old_actions = 'actions={FilledTonalIconButton(onClick=onOpenCalendar){Icon(Icons.Default.CalendarMonth,"Calendar")};Spacer(Modifier.width(4.dp));BadgedBox(badge={if(unreadCount>0)Badge{Text(if(unreadCount>99)"99+" else unreadCount.toString())}}){FilledTonalIconButton(onClick=onOpenMessenger){Icon(Icons.Default.ChatBubbleOutline,"Chat")}};Box{IconButton(onClick={menuOpen=true}){Icon(Icons.Default.MoreVert,"More")};DropdownMenu(menuOpen,{menuOpen=false}){DropdownMenuItem(text={Text("Calendar & schedule")},onClick={menuOpen=false;onOpenCalendar()});DropdownMenuItem(text={Text("Account settings")},onClick={menuOpen=false;showAccountSettings=true});DropdownMenuItem(text={Text("Sign out")},onClick={menuOpen=false;scope.launch{runCatching{repository.signOut()};onSignedOut()}})}};Spacer(Modifier.width(4.dp))}'
new_actions = 'actions={Box{IconButton(onClick={menuOpen=true}){Icon(Icons.Default.MoreVert,"More")};DropdownMenu(menuOpen,{menuOpen=false}){DropdownMenuItem(text={Text("Account settings")},onClick={menuOpen=false;showAccountSettings=true});DropdownMenuItem(text={Text("Sign out")},onClick={menuOpen=false;scope.launch{runCatching{repository.signOut()};onSignedOut()}})}};Spacer(Modifier.width(4.dp))}'
if old_actions not in s:
    raise SystemExit('Feed top bar anchor not found')
s = s.replace(old_actions, new_actions, 1)
old_fab = 'floatingActionButton={Column(horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(10.dp)){SmallFloatingActionButton(onClick={if(!uploading&&pendingUploadUris.isEmpty())openCamera()}){Icon(Icons.Default.CameraAlt,"Take photo")};ExtendedFloatingActionButton(onClick={if(!uploading&&pendingUploadUris.isEmpty())picker.launch(arrayOf("image/*"))},icon={if(uploading)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp) else Icon(Icons.Default.AddPhotoAlternate,"Add photos")},text={Text(if(uploading)uploadProgress?:"Uploading" else "Share photos")})}}'
new_fab = '''floatingActionButton={Box{FloatingActionButton(onClick={if(!uploading&&pendingUploadUris.isEmpty())addMenuOpen=true}){if(uploading)CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)else Icon(Icons.Default.Add,"Add")};DropdownMenu(expanded=addMenuOpen,onDismissRequest={addMenuOpen=false}){DropdownMenuItem(text={Text("Upload image")},leadingIcon={Icon(Icons.Default.AddPhotoAlternate,null)},onClick={addMenuOpen=false;picker.launch(arrayOf("image/*"))});DropdownMenuItem(text={Text("Camera")},leadingIcon={Icon(Icons.Default.CameraAlt,null)},onClick={addMenuOpen=false;openCamera()});DropdownMenuItem(text={Text("Now Together")},leadingIcon={Text("❤️")},onClick={addMenuOpen=false;composerError="Now Together is being prepared for this v1.0.31 branch."})}}}'''
if old_fab not in s:
    raise SystemExit('Feed FAB anchor not found')
s = s.replace(old_fab, new_fab, 1)
s = s.replace('Tap Share photos or the camera to add the first one.', 'Tap + to add your first moment.')
feed.write_text(s)
