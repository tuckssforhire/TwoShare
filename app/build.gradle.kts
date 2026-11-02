plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.twoshare.app"
    compileSdk = 36
    defaultConfig { applicationId = "com.twoshare.app"; minSdk = 26; targetSdk = 36; versionCode = 54; versionName = "1.0.30" }
    signingConfigs { getByName("debug") { storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore"); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android" } }
    buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("debug") }; getByName("release") { isDebuggable = false; isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { jvmToolchain(17) }

tasks.configureEach {
    if (name == "preBuild") {
        doFirst {
            val repo = file("src/main/java/com/twoshare/app/data/TwoShareRepository.kt")
            var text = repo.readText()
            text = text.replace("        require(emoji in allowedReactions)", "        require(emoji.isNotBlank() && emoji.codePointCount(0, emoji.length) <= 16)")
            repo.writeText(text)

            val messenger = file("src/main/java/com/twoshare/app/ui/MessengerScreenV2.kt")
            var messengerText = messenger.readText()
            if (!messengerText.contains("androidx.compose.foundation.lazy.grid.*")) {
                messengerText = messengerText.replace("import androidx.compose.foundation.lazy.*", "import androidx.compose.foundation.lazy.*\nimport androidx.compose.foundation.lazy.grid.*")
            }
            if (!messengerText.contains("import androidx.compose.ui.platform.LocalDensity")) {
                messengerText = messengerText.replace("import androidx.compose.ui.platform.LocalContext", "import androidx.compose.ui.platform.LocalContext\nimport androidx.compose.ui.platform.LocalDensity")
            }
            messengerText = messengerText.replace("\n }\n if(heartBurst>0)key(heartBurst){FloatingThinkingHeart()}", "\n if(heartBurst>0)key(heartBurst){FloatingThinkingHeart()}")
            messengerText = messengerText.replace("bottomBar={Surface(shadowElevation=6.dp){", "bottomBar={Surface(shadowElevation=6.dp){")
            messengerText = messengerText.replace("var themePicker by remember{mutableStateOf(false)}", "var themePicker by remember{mutableStateOf(false)};var templatePicker by remember{mutableStateOf(false)};var templates by remember{mutableStateOf(chatPrefs.getString(\"templates\",\"\").orEmpty().split(\"|||TS|||\").filter{it.isNotBlank()})}")
            messengerText = messengerText.replace("var thinkingLocked by remember{mutableStateOf(false)}", "var thinkingLocked by remember{mutableStateOf(false)};val imeBottom=WindowInsets.ime.getBottom(LocalDensity.current);val imeVisible=imeBottom>0")
            messengerText = messengerText.replace("LaunchedEffect(replying?.id,editing?.id){if((replying!=null||editing!=null)&&timeline.isNotEmpty()){delay(100);runCatching{listState.animateScrollToItem(timeline.lastIndex)}}}", "LaunchedEffect(replying?.id,editing?.id){if((replying!=null||editing!=null)&&timeline.isNotEmpty()){delay(100);runCatching{listState.animateScrollToItem(timeline.lastIndex)}}}\n LaunchedEffect(imeVisible,timeline.size){if(imeVisible&&timeline.isNotEmpty()){delay(180);runCatching{listState.animateScrollToItem(timeline.lastIndex)}}}")
            messengerText = messengerText.replace("title={Column{Text(partner?.displayName?:\"TwoShare\",style=MaterialTheme.typography.titleLarge);Text(partnerStatus,style=MaterialTheme.typography.labelSmall,color=if(partnerStatus==\"Online\")MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}}", "title={Row(verticalAlignment=Alignment.CenterVertically){Surface(shape=CircleShape,modifier=Modifier.size(40.dp).clickable(enabled=!thinkingLocked){thinkingLocked=true;heartBurst++;draft=\"❤️ Thinking of you\";send();scope.launch{delay(5000);thinkingLocked=false}}){if(partnerPhoto!=null)AsyncImage(partnerPhoto,\"${'$'}{partner?.displayName?:\"Partner\"} profile\",Modifier.fillMaxSize(),contentScale=ContentScale.Crop)else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Icon(Icons.Default.Person,\"Partner profile\")}};Spacer(Modifier.width(9.dp));Column{Text(partner?.displayName?:\"TwoShare\",style=MaterialTheme.typography.titleLarge);Text(if(thinkingLocked)\"Thinking of you sent · 5s\" else partnerStatus,style=MaterialTheme.typography.labelSmall,color=if(partnerStatus==\"Online\"&&!thinkingLocked)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}}}")
            messengerText = messengerText.replace("DropdownMenuItem(text={Column{Text(\"Thinking of you\");Text(if(thinkingLocked)\"Just sent · wait a moment\" else \"Send a little heart\",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}},leadingIcon={Icon(Icons.Default.Favorite,null)},enabled=!thinkingLocked,onClick={headerMenu=false;if(!thinkingLocked){thinkingLocked=true;heartBurst++;draft=\"❤️ Thinking of you\";send();scope.launch{delay(1000);thinkingLocked=false}}});", "")
            messengerText = messengerText.replace("DropdownMenuItem(text={Column{Text(\"Chat theme\");Text(\"Personalize your conversation\",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}},leadingIcon={Icon(Icons.Default.Palette,null)},onClick={headerMenu=false;themePicker=true})", "DropdownMenuItem(text={Column{Text(\"Message templates\");Text(\"Saved quick messages\",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}},leadingIcon={Icon(Icons.Default.Description,null)},onClick={headerMenu=false;templatePicker=true});DropdownMenuItem(text={Column{Text(\"Chat theme\");Text(\"Personalize your conversation\",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}},leadingIcon={Icon(Icons.Default.Palette,null)},onClick={headerMenu=false;themePicker=true})")
            messengerText = messengerText.replace("if(themePicker)ThemePickerDialog", "if(templatePicker)MessageTemplateDialog(templates,{text->draft=text;editing=null;replying=null;templatePicker=false},{updated->templates=updated;chatPrefs.edit().putString(\"templates\",updated.joinToString(\"|||TS|||\")).apply()},{templatePicker=false})\n if(themePicker)ThemePickerDialog")
            messenger.writeText(messengerText)

            val feed = file("src/main/java/com/twoshare/app/ui/FeedScreen.kt")
            var feedText = feed.readText()
            feedText = feedText.replace("import androidx.compose.foundation.lazy.LazyColumn\nimport androidx.compose.foundation.lazy.items", "import androidx.compose.foundation.lazy.LazyColumn\nimport androidx.compose.foundation.lazy.items\nimport androidx.compose.foundation.lazy.rememberLazyListState")
            feedText = feedText.replace("import androidx.compose.material.icons.filled.MoreVert", "import androidx.compose.material.icons.filled.MoreVert\nimport androidx.compose.material.icons.filled.Notifications")
            feedText = feedText.replace("val context=LocalContext.current;val scope=rememberCoroutineScope();val presence=remember{PresenceRepository()};val reactionRepo=remember(repository){FeedReactionRepository(repository)};", "val context=LocalContext.current;val scope=rememberCoroutineScope();val presence=remember{PresenceRepository()};val reactionRepo=remember(repository){FeedReactionRepository(repository)};val feedNotificationRepo=remember(repository){FeedNotificationRepository(repository)};val feedListState=rememberLazyListState();")
            feedText = feedText.replace("var avatarRefresh by remember{mutableLongStateOf(0L)}", "var avatarRefresh by remember{mutableLongStateOf(0L)};var feedNotifications by remember{mutableStateOf(emptyList<FeedNotification>())};var showFeedNotifications by remember{mutableStateOf(false)}")
            feedText = feedText.replace("LaunchedEffect(Unit){refresh(true);while(isActive){delay(10000);", "LaunchedEffect(Unit){refresh(true);feedNotifications=runCatching{feedNotificationRepo.load()}.getOrDefault(emptyList());while(isActive){delay(10000);feedNotifications=runCatching{feedNotificationRepo.load()}.getOrDefault(feedNotifications);")
            feedText = feedText.replace("actions={FilledTonalIconButton(onClick=onOpenCalendar)", "actions={BadgedBox(badge={val n=feedNotifications.count{!it.isRead};if(n>0)Badge{Text(if(n>99)\"99+\" else n.toString())}}){FilledTonalIconButton(onClick={showFeedNotifications=true}){Icon(Icons.Default.Notifications,\"Feed notifications\")}};Spacer(Modifier.width(4.dp));FilledTonalIconButton(onClick=onOpenCalendar)")
            feedText = feedText.replace("else->LazyColumn(Modifier.fillMaxSize(),contentPadding=", "else->LazyColumn(state=feedListState,modifier=Modifier.fillMaxSize(),contentPadding=")
            feedText = feedText.replace("PostCard(post,repository.currentUserId(),avatarRefresh", "PostCard(post,repository,repository.currentUserId(),avatarRefresh")
            feedText = feedText.replace("PostCard(post:FeedPost,currentUid:String?", "PostCard(post:FeedPost,repository:TwoShareRepository,currentUid:String?")
            feedText = feedText.replace("if(reactions.isNotEmpty())Text(reactions.entries.joinToString(\"  \"){\"${'$'}{it.key} ${'$'}{it.value.size}\"},Modifier.padding(horizontal=14.dp,vertical=5.dp),style=MaterialTheme.typography.labelMedium);HorizontalDivider", "if(reactions.isNotEmpty())Text(reactions.entries.joinToString(\"  \"){\"${'$'}{it.key} ${'$'}{it.value.size}\"},Modifier.padding(horizontal=14.dp,vertical=5.dp),style=MaterialTheme.typography.labelMedium);FeedCommentPreview(post,repository,onComments);HorizontalDivider")
            feedText = feedText.replace("Box{TextButton(onClick={onReact(mine?:\"❤️\")},modifier=Modifier.combinedClickable(onClick={onReact(mine?:\"❤️\")},onLongClick={reactionMenu=true})){Text(mine?:\"♡\");Spacer(Modifier.width(6.dp));Text(if(total==0)\"React\" else total.toString())};DropdownMenu(reactionMenu,{reactionMenu=false}){Row(Modifier.padding(horizontal=8.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)){choices.forEach{emoji->Text(emoji,style=MaterialTheme.typography.headlineSmall,modifier=Modifier.clickable{reactionMenu=false;onReact(emoji)}.padding(5.dp))}}}}", "FeedReactionButton(mine,total,choices,onReact)")
            feedText = feedText.replace("TextButton(onClick=onComments){Text(\"Comment\")}", "TextButton(onClick={FeedCommentExpansion.toggle(post.id)}){Text(\"Comment\")}")
            feedText = feedText.replace("if(showAccountSettings)AccountSettingsDialog", "if(showFeedNotifications)FeedNotificationsDialog(feedNotifications,{showFeedNotifications=false},{notification->scope.launch{runCatching{feedNotificationRepo.markRead(notification.id)};feedNotifications=runCatching{feedNotificationRepo.load()}.getOrDefault(feedNotifications);showFeedNotifications=false;val index=posts.indexOfFirst{it.id==notification.postId};if(index>=0){if(notification.opensComments)FeedCommentExpansion.expand(notification.postId);feedListState.animateScrollToItem(index)}}},{scope.launch{runCatching{feedNotificationRepo.markAllRead()};feedNotifications=runCatching{feedNotificationRepo.load()}.getOrDefault(feedNotifications)}});if(showAccountSettings)AccountSettingsDialog")
            feed.writeText(feedText)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.compose.ui:ui:1.7.8")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.8")
    implementation("androidx.compose.foundation:foundation:1.7.8")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.8")
    implementation(platform("io.github.jan-tennert.supabase:bom:3.8.0"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:storage-kt")
    implementation("io.github.jan-tennert.supabase:functions-kt")
    implementation("io.ktor:ktor-client-android:3.6.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    implementation(platform("com.google.firebase:firebase-bom:34.7.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
}
