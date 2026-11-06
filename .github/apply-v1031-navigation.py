from pathlib import Path

main = Path('app/src/main/java/com/twoshare/app/MainActivity.kt')
s = main.read_text()
old = 'else if(showCalendar)CalendarScreen(repository){showCalendar=false}else if(showMessenger)MessengerScreenV2(repository,{showMessenger=false}){startedCall,incoming->incomingCall=null;activeCallIncoming=incoming;activeCall=startedCall}else FeedScreen(repository,authManager,selectedTheme,::chooseTheme,{showMessenger=true;showCalendar=false},{showCalendar=true;showMessenger=false}){scope.launch'
new = '''else Scaffold(bottomBar={var navLocked by remember{mutableStateOf(false)};val navScope=rememberCoroutineScope();TwoShareBottomNavigation(selected=when{showCalendar->TwoShareTab.Calendar;showMessenger->TwoShareTab.Messenger;else->TwoShareTab.Feed}){tab->if(!navLocked){val alreadySelected=when(tab){TwoShareTab.Feed->!showCalendar&&!showMessenger;TwoShareTab.Messenger->showMessenger;TwoShareTab.Calendar->showCalendar};if(!alreadySelected){navLocked=true;when(tab){TwoShareTab.Feed->{showCalendar=false;showMessenger=false};TwoShareTab.Messenger->{showCalendar=false;showMessenger=true};TwoShareTab.Calendar->{showMessenger=false;showCalendar=true}};navScope.launch{delay(350);navLocked=false}}}}}){navPadding->Box(Modifier.fillMaxSize().padding(navPadding)){when{showCalendar->CalendarScreen(repository){showCalendar=false};showMessenger->MessengerScreenV2(repository,{showMessenger=false}){startedCall,incoming->incomingCall=null;activeCallIncoming=incoming;activeCall=startedCall};else->FeedScreen(repository,authManager,selectedTheme,::chooseTheme,{showMessenger=true;showCalendar=false},{showCalendar=true;showMessenger=false}){scope.launch'''
if old not in s: raise SystemExit('MainActivity navigation anchor not found')
s = s.replace(old,new,1)
old2='biometricLockEnabled=false;biometricRequired=false;signedIn=false}};else->LoginScreen'
new2='biometricLockEnabled=false;biometricRequired=false;signedIn=false}}}}};else->LoginScreen'
if old2 not in s: raise SystemExit('MainActivity navigation close anchor not found')
s=s.replace(old2,new2,1);main.write_text(s)

feed=Path('app/src/main/java/com/twoshare/app/ui/FeedScreen.kt');s=feed.read_text()
s=s.replace('import androidx.compose.material.icons.filled.AddPhotoAlternate\n','import androidx.compose.material.icons.filled.Add\nimport androidx.compose.material.icons.filled.AddPhotoAlternate\n')
s=s.replace('import com.twoshare.app.model.FeedPost\n','import com.twoshare.app.model.FeedPost\nimport com.twoshare.app.model.NowTogetherSessionRow\n')
s=s.replace('var menuOpen by remember{mutableStateOf(false)};','var menuOpen by remember{mutableStateOf(false)};var addMenuOpen by remember{mutableStateOf(false)};var pendingCameraUri by remember{mutableStateOf<Uri?>(null)};var cameraMode by remember{mutableStateOf("post")};var nowCaption by remember{mutableStateOf("")};var nowCaptionOpen by remember{mutableStateOf(false)};var nowCapturedUri by remember{mutableStateOf<Uri?>(null)};var nowCaptureMode by remember{mutableStateOf("now_start")};var nowSubmitError by remember{mutableStateOf<String?>(null)};var nowPending by remember{mutableStateOf<NowTogetherSessionRow?>(null)};var nowBusy by remember{mutableStateOf(false)};var nowMessage by remember{mutableStateOf<String?>(null)};')
s=s.replace('val context=LocalContext.current;val scope=rememberCoroutineScope();val presence=remember{PresenceRepository()};','val context=LocalContext.current;val scope=rememberCoroutineScope();val presence=remember{PresenceRepository()};val nowRepo=remember(repository){NowTogetherRepository(repository)};')
old_camera='val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()){bitmap->if(bitmap!=null){val uri=savePostCameraBitmap(context,bitmap);if(uri!=null){composerError=null;pendingUploadUris=listOf(uri)}else composerError="Unable to save camera photo."}}\n val cameraPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)camera.launch(null)else composerError="Camera permission is needed to take a photo."}\n fun openCamera(){if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)camera.launch(null)else cameraPermission.launch(Manifest.permission.CAMERA)}'
new_camera='''val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){saved->
  val uri=pendingCameraUri
  val mode=cameraMode
  pendingCameraUri=null
  if(saved&&uri!=null){
   if(mode=="now_start"||mode=="now_reply"){
    nowCapturedUri?.takeIf{it!=uri}?.let{old->runCatching{context.contentResolver.delete(old,null,null)}}
    nowCapturedUri=uri
    nowCaptureMode=mode
    nowCaptionOpen=true
    nowSubmitError=null
   }else{
    composerError=null
    pendingUploadUris=listOf(uri)
   }
  }else{
   uri?.let{runCatching{context.contentResolver.delete(it,null,null)}}
   if(mode.startsWith("now_")&&nowCapturedUri!=null)nowCaptionOpen=true
  }
  cameraMode="post"
 }
 fun launchFullCamera(mode:String){
  cameraMode=mode
  runCatching{
   val values=ContentValues().apply{
    put(MediaStore.Images.Media.DISPLAY_NAME,"TwoShare_"+System.currentTimeMillis()+".jpg")
    put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
    put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/TwoShare")
   }
   context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)
  }.onSuccess{uri->
   if(uri!=null){pendingCameraUri=uri;camera.launch(uri)}
   else if(mode.startsWith("now_")){nowSubmitError="Unable to prepare camera photo.";nowCaptionOpen=nowCapturedUri!=null}
   else composerError="Unable to prepare camera photo."
  }.onFailure{
   if(mode.startsWith("now_")){nowSubmitError=friendlyError(it,"Unable to open camera.");nowCaptionOpen=nowCapturedUri!=null}
   else composerError=friendlyError(it,"Unable to open camera.")
  }
 }
 val cameraPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
  if(granted)launchFullCamera(cameraMode)
  else if(cameraMode.startsWith("now_")){nowSubmitError="Camera permission is needed to take a photo.";nowCaptionOpen=nowCapturedUri!=null}
  else composerError="Camera permission is needed to take a photo."
 }
 fun openCamera(mode:String="post"){
  cameraMode=mode
  if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)launchFullCamera(mode)
  else cameraPermission.launch(Manifest.permission.CAMERA)
 }'''
if old_camera not in s: raise SystemExit('camera anchor missing')
s=s.replace(old_camera,new_camera,1)
s=s.replace('LaunchedEffect(Unit){while(isActive){delay(60000);avatarRefresh++}}','LaunchedEffect(Unit){while(isActive){delay(60000);avatarRefresh++}}\n LaunchedEffect(Unit){while(isActive){runCatching{nowRepo.pendingForMe()}.onSuccess{if(it!=null&&nowPending?.id!=it.id)nowPending=it};delay(20000)}}')
old_actions='actions={FilledTonalIconButton(onClick=onOpenCalendar){Icon(Icons.Default.CalendarMonth,"Calendar")};Spacer(Modifier.width(4.dp));BadgedBox(badge={if(unreadCount>0)Badge{Text(if(unreadCount>99)"99+" else unreadCount.toString())}}){FilledTonalIconButton(onClick=onOpenMessenger){Icon(Icons.Default.ChatBubbleOutline,"Chat")}};Box{IconButton(onClick={menuOpen=true}){Icon(Icons.Default.MoreVert,"More")};DropdownMenu(menuOpen,{menuOpen=false}){DropdownMenuItem(text={Text("Calendar & schedule")},onClick={menuOpen=false;onOpenCalendar()});DropdownMenuItem(text={Text("Account settings")},onClick={menuOpen=false;showAccountSettings=true});DropdownMenuItem(text={Text("Sign out")},onClick={menuOpen=false;scope.launch{runCatching{repository.signOut()};onSignedOut()}})}};Spacer(Modifier.width(4.dp))}'
new_actions='actions={Box{IconButton(onClick={menuOpen=true}){Icon(Icons.Default.MoreVert,"More")};DropdownMenu(menuOpen,{menuOpen=false}){DropdownMenuItem(text={Text("Account settings")},onClick={menuOpen=false;showAccountSettings=true});DropdownMenuItem(text={Text("Sign out")},onClick={menuOpen=false;scope.launch{runCatching{repository.signOut()};onSignedOut()}})}};Spacer(Modifier.width(4.dp))}'
old_actions=old_actions.replace('runCatching{','runCatchingActive{')
new_actions=new_actions.replace('runCatching{','runCatchingActive{')
if old_actions not in s: raise SystemExit('actions anchor missing')
s=s.replace(old_actions,new_actions,1)
old_fab='floatingActionButton={Column(horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(10.dp)){SmallFloatingActionButton(onClick={if(!uploading&&pendingUploadUris.isEmpty())openCamera()}){Icon(Icons.Default.CameraAlt,"Take photo")};ExtendedFloatingActionButton(onClick={if(!uploading&&pendingUploadUris.isEmpty())picker.launch(arrayOf("image/*"))},icon={if(uploading)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp) else Icon(Icons.Default.AddPhotoAlternate,"Add photos")},text={Text(if(uploading)uploadProgress?:"Uploading" else "Share photos")})}}'
new_fab='''floatingActionButton={Box{FloatingActionButton(onClick={if(!uploading&&!nowBusy&&pendingUploadUris.isEmpty())addMenuOpen=true}){if(uploading||nowBusy)CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)else Icon(Icons.Default.Add,"Add")};DropdownMenu(addMenuOpen,{addMenuOpen=false}){DropdownMenuItem(text={Text("Upload image")},leadingIcon={Icon(Icons.Default.AddPhotoAlternate,null)},onClick={addMenuOpen=false;picker.launch(arrayOf("image/*"))});DropdownMenuItem(text={Text("Camera")},leadingIcon={Icon(Icons.Default.CameraAlt,null)},onClick={addMenuOpen=false;openCamera()});DropdownMenuItem(text={Text("Now Together")},leadingIcon={Text("❤️")},onClick={addMenuOpen=false;nowCaption="";nowSubmitError=null;nowCapturedUri=null;nowCaptureMode="now_start";openCamera("now_start")})}}}'''
if old_fab not in s: raise SystemExit('fab anchor missing')
s=s.replace(old_fab,new_fab,1).replace('Tap Share photos or the camera to add the first one.','Tap + to add your first moment.')
# Keep the stable v1.0.30 caption path here. Now Together attribution is loaded separately, never by starting a query from every visible lazy-list item.
anchor=' if(showAccountSettings)AccountSettingsDialog(authManager,selectedTheme,onThemeSelected){showAccountSettings=false}'
insert=''' nowCapturedUri?.let{photoUri->if(nowCaptionOpen)AlertDialog(
  modifier=Modifier.imePadding(),
  onDismissRequest={if(!nowBusy){nowCaptionOpen=false;nowCapturedUri=null;nowCaption="";nowSubmitError=null;runCatching{context.contentResolver.delete(photoUri,null,null)}}},
  title={Text(if(nowCaptureMode=="now_reply")"Complete Now Together ❤️" else "Start Now Together ❤️")},
  text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
   Text("Your photo is ready. Add a caption before sharing.",style=MaterialTheme.typography.bodyMedium)
   AsyncImage(model=photoUri,contentDescription="Your captured Now Together photo",
    modifier=Modifier.fillMaxWidth().heightIn(max=290.dp).clip(RoundedCornerShape(18.dp)),
    contentScale=ContentScale.Fit)
   OutlinedTextField(nowCaption,{if(it.length<=500)nowCaption=it},
    label={Text("Caption (optional)")},placeholder={Text("Write about this moment…")},
    supportingText={Text(nowCaption.length.toString()+" / 500")},maxLines=4,
    modifier=Modifier.fillMaxWidth(),enabled=!nowBusy)
   nowSubmitError?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
  }},
  confirmButton={Button(enabled=!nowBusy,onClick={
   if(!nowBusy)scope.launch{
    nowBusy=true
    nowSubmitError=null
    val mode=nowCaptureMode
    runCatching{
     if(mode=="now_reply"){
      val session=nowPending?:error("The Now Together request is no longer available.")
      nowRepo.complete(context,session,photoUri,nowCaption)
     }else nowRepo.create(context,photoUri,nowCaption)
    }.onSuccess{
     nowCaptionOpen=false
     nowCapturedUri=null
     nowCaption=""
     if(mode=="now_reply"){nowPending=null;nowMessage="Now Together · Just now";refresh(true)}
     else nowMessage="Waiting for your partner to capture their moment…"
    }.onFailure{nowSubmitError=friendlyError(it,"Unable to share Now Together.")}
    nowBusy=false
   }
  }){if(nowBusy)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)else Text("Share photo")}},
  dismissButton={Row{
   TextButton(enabled=!nowBusy,onClick={nowCaptionOpen=false;openCamera(nowCaptureMode)}){Text("Retake")}
   TextButton(enabled=!nowBusy,onClick={
    nowCaptionOpen=false;nowCaption="";nowSubmitError=null;nowCapturedUri=null
    runCatching{context.contentResolver.delete(photoUri,null,null)}
   }){Text("Cancel")}
  }}
 )}
 nowMessage?.let{msg->AlertDialog(onDismissRequest={nowMessage=null},title={Text("Now Together")},text={Text(msg)},confirmButton={TextButton({nowMessage=null}){Text("OK")}})}
 if(!nowCaptionOpen)nowPending?.let{session->AlertDialog(
  onDismissRequest={},
  title={Text("Now Together ❤️")},
  text={Text("Your partner wants a Now Together with you. Take your photo first, then preview it and add your caption.")},
  confirmButton={Button(enabled=!nowBusy,onClick={
   scope.launch{
    nowBusy=true
    runCatching{nowRepo.markOpened(session)}.onSuccess{
     nowCaption="";nowSubmitError=null;nowCapturedUri=null;nowCaptureMode="now_reply";openCamera("now_reply")
    }.onFailure{nowMessage=friendlyError(it,"Now Together expired.");nowPending=null}
    nowBusy=false
   }
  }){Text(if(nowBusy)"Opening…" else "Take photo")}},
  dismissButton={TextButton(onClick={nowPending=null}){Text("Not now")}}
 )}
'''+anchor
if anchor not in s: raise SystemExit('dialog anchor missing')
s=s.replace(anchor,insert,1)
# Keep dynamically generated screen callbacks cancellation-safe as well.
s=s.replace('runCatching{','runCatchingActive{')
feed.write_text(s)
