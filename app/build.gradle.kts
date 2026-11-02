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
    defaultConfig { applicationId = "com.twoshare.app"; minSdk = 26; targetSdk = 36; versionCode = 51; versionName = "1.0.27" }
    signingConfigs { getByName("debug") { storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore"); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android" } }
    buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("debug") }; getByName("release") { isDebuggable = false; isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { jvmToolchain(17) }

// Messenger reaction compatibility. The database accepts Unicode reaction strings up to 16 characters.
// CI currently injects the expanded picker, so normalize the generated source before compilation.
tasks.configureEach {
    if (name == "preBuild") {
        doFirst {
            val repo = file("src/main/java/com/twoshare/app/data/TwoShareRepository.kt")
            var text = repo.readText()
            text = text.replace("        require(emoji in allowedReactions)", "        require(emoji.isNotBlank() && emoji.codePointCount(0, emoji.length) <= 16)")
            repo.writeText(text)

            val messenger = file("src/main/java/com/twoshare/app/ui/MessengerScreenV2.kt")
            var ui = messenger.readText()
            val shortList = "val all=listOf(\"❤️\",\"👍\",\"😂\",\"😍\",\"🥰\",\"😘\",\"😊\",\"😁\",\"🤣\",\"😮\",\"😢\",\"😭\",\"😡\",\"🤔\",\"🙏\",\"👏\",\"🎉\",\"🔥\",\"💯\",\"✨\",\"💪\",\"👌\",\"🙌\",\"🤗\")"
            val broadList = "val all=listOf(\"😀\",\"😃\",\"😄\",\"😁\",\"😆\",\"😅\",\"🤣\",\"😂\",\"🙂\",\"🙃\",\"😉\",\"😊\",\"😇\",\"🥰\",\"😍\",\"🤩\",\"😘\",\"😗\",\"☺️\",\"😚\",\"😙\",\"🥲\",\"😋\",\"😛\",\"😜\",\"🤪\",\"😝\",\"🤑\",\"🤗\",\"🤭\",\"🫢\",\"🫣\",\"🤫\",\"🤔\",\"🫡\",\"🤐\",\"🤨\",\"😐\",\"😑\",\"😶\",\"🫥\",\"😏\",\"😒\",\"🙄\",\"😬\",\"🤥\",\"😌\",\"😔\",\"😪\",\"🤤\",\"😴\",\"😷\",\"🤒\",\"🤕\",\"🤢\",\"🤮\",\"🤧\",\"🥵\",\"🥶\",\"🥴\",\"😵\",\"🤯\",\"🤠\",\"🥳\",\"🥸\",\"😎\",\"🤓\",\"🧐\",\"😕\",\"🫤\",\"😟\",\"🙁\",\"☹️\",\"😮\",\"😯\",\"😲\",\"😳\",\"🥺\",\"🥹\",\"😦\",\"😧\",\"😨\",\"😰\",\"😥\",\"😢\",\"😭\",\"😱\",\"😖\",\"😣\",\"😞\",\"😓\",\"😩\",\"😫\",\"🥱\",\"😤\",\"😡\",\"😠\",\"🤬\",\"😈\",\"👿\",\"💀\",\"☠️\",\"💩\",\"🤡\",\"👹\",\"👺\",\"👻\",\"👽\",\"🤖\",\"😺\",\"😸\",\"😹\",\"😻\",\"😼\",\"😽\",\"🙀\",\"😿\",\"😾\",\"❤️\",\"🩷\",\"🧡\",\"💛\",\"💚\",\"💙\",\"🩵\",\"💜\",\"🤎\",\"🖤\",\"🩶\",\"🤍\",\"💔\",\"❤️‍🔥\",\"❤️‍🩹\",\"💕\",\"💞\",\"💓\",\"💗\",\"💖\",\"💘\",\"💝\",\"💟\",\"💯\",\"💢\",\"💥\",\"💫\",\"💦\",\"💨\",\"💬\",\"💭\",\"💤\",\"👋\",\"🤚\",\"🖐️\",\"✋\",\"🖖\",\"🫱\",\"🫲\",\"🫳\",\"🫴\",\"👌\",\"🤌\",\"🤏\",\"✌️\",\"🤞\",\"🫰\",\"🤟\",\"🤘\",\"🤙\",\"👈\",\"👉\",\"👆\",\"👇\",\"☝️\",\"🫵\",\"👍\",\"👎\",\"✊\",\"👊\",\"🤛\",\"🤜\",\"👏\",\"🙌\",\"🫶\",\"👐\",\"🤲\",\"🤝\",\"🙏\",\"✍️\",\"💅\",\"🤳\",\"💪\",\"🦾\",\"🧠\",\"👀\",\"👁️\",\"👄\",\"🫦\",\"👶\",\"🧒\",\"👦\",\"👧\",\"🧑\",\"👨\",\"👩\",\"🧓\",\"👴\",\"👵\",\"🔥\",\"✨\",\"⭐\",\"🌟\",\"🎉\",\"🎊\",\"🎈\",\"🎁\",\"🏆\",\"🥇\",\"⚡\",\"☀️\",\"🌙\",\"🌈\",\"🌹\",\"🌸\",\"🌺\",\"🍀\",\"🍕\",\"🍔\",\"🍟\",\"🍰\",\"🎂\",\"☕\",\"🍻\",\"🥂\",\"⚽\",\"🏀\",\"🏈\",\"⚾\",\"🎮\",\"🎵\",\"🎶\",\"🚗\",\"✈️\",\"🚀\",\"🏠\",\"📱\",\"💡\",\"✅\",\"❌\",\"❗\",\"❓\",\"‼️\",\"⁉️\",\"✔️\",\"☑️\",\"🔴\",\"🟠\",\"🟡\",\"🟢\",\"🔵\",\"🟣\",\"⚫\",\"⚪\")"
            ui = ui.replace(shortList, broadList)
            val fixedGrid = "text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){all.chunked(6).forEach{row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){row.forEach{e->Surface(shape=CircleShape,color=MaterialTheme.colorScheme.surfaceContainerHigh,modifier=Modifier.size(42.dp).clickable{onEmoji(e)}){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(e,fontSize=24.sp)}}}}}}}"
            val scrollGrid = "text={LazyColumn(Modifier.fillMaxWidth().heightIn(max=520.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){items(all.chunked(6)){row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){row.forEach{e->Surface(shape=CircleShape,color=MaterialTheme.colorScheme.surfaceContainerHigh,modifier=Modifier.size(42.dp).clickable{onEmoji(e)}){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(e,fontSize=24.sp)}}}}}}}"
            ui = ui.replace(fixedGrid, scrollGrid)
            messenger.writeText(ui)
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
