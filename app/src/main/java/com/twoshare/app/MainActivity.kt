package com.twoshare.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.twoshare.app.data.*
import com.twoshare.app.model.CallRow
import com.twoshare.app.push.PushNotificationManager
import com.twoshare.app.ui.*
import com.twoshare.app.ui.theme.*
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity:FragmentActivity(){
 private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){}
 private var backgroundedAt:Long=0L;private var biometricLockEnabled=false;private var requestBiometricAfterResume=false
 private var pendingOpenCalendar=false;private var pendingCalendarEventId:String?=null
 private fun captureLaunchIntent(source:Intent?){if(source?.getBooleanExtra("open_calendar",false)==true){pendingOpenCalendar=true;pendingCalendarEventId=source.getStringExtra("calendar_event_id")}}
 override fun onNewIntent(newIntent:Intent){super.onNewIntent(newIntent);setIntent(newIntent);captureLaunchIntent(newIntent)}
 override fun onStop(){super.onStop();if(!isChangingConfigurations){LiveUpdates.pause();if(biometricLockEnabled)backgroundedAt=System.currentTimeMillis()}}
 override fun onStart(){super.onStart();if(biometricLockEnabled&&backgroundedAt>0L&&System.currentTimeMillis()-backgroundedAt>=BIOMETRIC_GRACE_MS)requestBiometricAfterResume=true;backgroundedAt=0L;LiveUpdates.resume(connect=!requestBiometricAfterResume)}
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);captureLaunchIntent(intent);OfflineCache.init(this);LocalImageCache.install(this);ProfilePhotoStore.init(this);SupabaseProvider.client.handleDeeplinks(intent);setContent{
  val context=LocalContext.current;var themeUserId by remember{mutableStateOf(SupabaseProvider.client.auth.currentUserOrNull()?.id)};var selectedTheme by remember{mutableStateOf(loadTheme(context,themeUserId))}
  TwoShareTheme(theme=selectedTheme){
   val repository=remember{TwoShareRepository()};val chatFeatures=remember(repository){ChatFeaturesRepository(repository)};val authManager=remember{AuthManager()};val versionRepository=remember{AppVersionRepository()};val scope=rememberCoroutineScope()
   var authChecked by remember{mutableStateOf(false)};var signedIn by remember{mutableStateOf(false)};var biometricRequired by remember{mutableStateOf(false)};var biometricError by remember{mutableStateOf<String?>(null)};var biometricPromptVersion by remember{mutableIntStateOf(0)}
   var showMessenger by remember{mutableStateOf(intent.getBooleanExtra("open_messenger",false))};var showCalendar by remember{mutableStateOf(pendingOpenCalendar||intent.getBooleanExtra("open_calendar",false))};var activeCall by remember{mutableStateOf<CallRow?>(null)};var activeCallIncoming by remember{mutableStateOf(false)};var incomingCall by remember{mutableStateOf<CallRow?>(null)};var updateInfo by remember{mutableStateOf<AppVersionInfo?>(null)};var showExitConfirmation by remember{mutableStateOf(false)};var showLists by remember{mutableStateOf(false)};var showWhatsNew by remember{mutableStateOf(false)}
   fun refreshThemeForCurrentUser(){themeUserId=SupabaseProvider.client.auth.currentUserOrNull()?.id;selectedTheme=loadTheme(context,themeUserId)}
   fun chooseTheme(theme:String){selectedTheme=theme;saveTheme(context,themeUserId,theme)}
   fun applyPendingCalendar(){if(pendingOpenCalendar){showMessenger=false;showLists=false;showCalendar=true;pendingOpenCalendar=false}}
   BackHandler(enabled=authChecked&&signedIn&&!biometricRequired&&activeCall==null){when{showCalendar->showCalendar=false;showMessenger->showMessenger=false;showLists->showLists=false;else->showExitConfirmation=true}}
   LaunchedEffect(signedIn,biometricRequired){if(signedIn&&!biometricRequired)LiveUpdates.start() else{LiveUpdates.stop();if(!signedIn){showLists=false;SharedListsRepository.clearCache()}}}
   LaunchedEffect(Unit){runCatchingActive{versionRepository.latestVersion()}.getOrNull()?.takeIf{it.latestVersionCode>BuildConfig.VERSION_CODE}?.let{updateInfo=it}}
   LaunchedEffect(Unit){val startedAt=System.currentTimeMillis();val restored=authManager.restoreTrustedSession(this@MainActivity);biometricLockEnabled=restored&&authManager.isBiometricEnabled(this@MainActivity);if(restored&&biometricLockEnabled){if(authManager.canUseBiometrics(this@MainActivity))biometricRequired=true else{runCatchingActive{repository.signOut()};signedIn=false}}else signedIn=restored;if(restored)refreshThemeForCurrentUser();val remainingSplash=700L-(System.currentTimeMillis()-startedAt);if(remainingSplash>0)delay(remainingSplash);authChecked=true}
   DisposableEffect(Unit){val observer=androidx.lifecycle.LifecycleEventObserver{_,event->if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME){if(requestBiometricAfterResume&&signedIn&&authManager.isBiometricEnabled(this@MainActivity)){requestBiometricAfterResume=false;biometricRequired=true}else if(signedIn&&!biometricRequired)applyPendingCalendar()}};lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}}
   LaunchedEffect(signedIn,biometricRequired){biometricLockEnabled=signedIn&&authManager.isBiometricEnabled(this@MainActivity);if(signedIn&&!biometricRequired){applyPendingCalendar();refreshThemeForCurrentUser();if(shouldShowWhatsNew(context,BuildConfig.VERSION_CODE))showWhatsNew=true;if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this@MainActivity,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);runCatchingActive{PushNotificationManager.registerCurrentDevice(this@MainActivity)}}}
   LaunchedEffect(Unit){SupabaseProvider.client.auth.sessionStatus.collect{status->if(!authChecked)return@collect;when(status){is SessionStatus.Authenticated->Unit;SessionStatus.Initializing,is SessionStatus.RefreshFailure->Unit;is SessionStatus.NotAuthenticated->{if(!signedIn||biometricRequired)return@collect;delay(750);if(SupabaseProvider.client.auth.currentUserOrNull()==null){showMessenger=false;showCalendar=false;activeCall=null;activeCallIncoming=false;incomingCall=null;signedIn=false}}}}}
   LaunchedEffect(authChecked,biometricRequired,biometricPromptVersion){if(!authChecked||!biometricRequired)return@LaunchedEffect;requestBiometricUnlock({biometricError=null;biometricRequired=false;signedIn=true},{biometricError=it})}
   LaunchedEffect(signedIn,activeCall){if(!authChecked||!signedIn||activeCall!=null)return@LaunchedEffect;var seenCalls=LiveUpdates.version(LiveUpdates.CALL_TABLES);while(isActive&&signedIn&&activeCall==null){runCatchingActive{chatFeatures.markIncomingDelivered()};val currentIncoming=incomingCall;if(currentIncoming==null)incomingCall=runCatchingActive{repository.findIncomingCall()}.getOrNull() else incomingCall=runCatchingActive{repository.getCall(currentIncoming.id)}.getOrNull()?.takeIf{it.status=="ringing"};seenCalls=LiveUpdates.awaitChange(LiveUpdates.CALL_TABLES,seenCalls,10_000L,1_500L)}}
   when{!authChecked->TwoShareStartupScreen();biometricRequired->BiometricUnlockScreen(biometricError,{biometricError=null;biometricPromptVersion++},{scope.launch{runCatchingActive{PushNotificationManager.unregisterCurrentDevice(this@MainActivity)};runCatchingActive{repository.signOut()};biometricLockEnabled=false;biometricRequired=false;biometricError=null;signedIn=false}});signedIn->{val call=activeCall;if(call!=null)CallScreen(repository,call,activeCallIncoming){activeCall=null;activeCallIncoming=false}else Scaffold(bottomBar={var navLocked by remember{mutableStateOf(false)};val navScope=rememberCoroutineScope();TwoShareBottomNavigation(selected=when{showCalendar->TwoShareTab.Calendar;showMessenger->TwoShareTab.Messenger;showLists->TwoShareTab.Lists;else->TwoShareTab.Feed}){tab->if(!navLocked){val alreadySelected=when(tab){TwoShareTab.Feed->!showCalendar&&!showMessenger&&!showLists;TwoShareTab.Messenger->showMessenger&&!showCalendar;TwoShareTab.Lists->showLists&&!showCalendar&&!showMessenger;TwoShareTab.Calendar->showCalendar};if(!alreadySelected){navLocked=true;when(tab){TwoShareTab.Feed->{showCalendar=false;showMessenger=false;showLists=false};TwoShareTab.Messenger->{showCalendar=false;showLists=false;showMessenger=true};TwoShareTab.Lists->{showCalendar=false;showMessenger=false;showLists=true};TwoShareTab.Calendar->{showMessenger=false;showLists=false;showCalendar=true}};navScope.launch{delay(350);navLocked=false}}}}}){navPadding->Box(Modifier.fillMaxSize().padding(navPadding)){when{showCalendar->CalendarScreen(repository){showCalendar=false};showMessenger->MessengerScreenV2(repository,{showMessenger=false}){startedCall,incoming->incomingCall=null;activeCallIncoming=incoming;activeCall=startedCall};showLists->SharedListsScreen(repository);else->FeedScreen(repository,authManager,selectedTheme,::chooseTheme,{showMessenger=true;showCalendar=false;showLists=false},{showCalendar=true;showMessenger=false;showLists=false}){scope.launch{runCatchingActive{PushNotificationManager.unregisterCurrentDevice(this@MainActivity)}};showMessenger=false;showCalendar=false;activeCall=null;incomingCall=null;biometricLockEnabled=false;biometricRequired=false;signedIn=false}}}}};else->LoginScreen(authManager){showMessenger=false;showCalendar=false;activeCall=null;incomingCall=null;biometricRequired=false;signedIn=true;biometricLockEnabled=authManager.isBiometricEnabled(this@MainActivity);refreshThemeForCurrentUser();applyPendingCalendar()}}
   if(showExitConfirmation)AlertDialog(onDismissRequest={showExitConfirmation=false},title={Text("Exit TwoShare?")},text={Text("Do you want to exit the app?")},confirmButton={TextButton(onClick={showExitConfirmation=false;finish()}){Text("Exit")}},dismissButton={TextButton(onClick={showExitConfirmation=false}){Text("Cancel")}})
   val ringing=incomingCall;if(authChecked&&signedIn&&activeCall==null&&ringing!=null)AlertDialog(onDismissRequest={},title={Text("Incoming TwoLink")},text={Text(if(ringing.isVideo)"Your TwoShare partner is starting a video TwoLink." else "Your TwoShare partner is starting a voice TwoLink.")},confirmButton={TextButton(onClick={incomingCall=null;activeCallIncoming=true;activeCall=ringing}){Text("Answer")}},dismissButton={TextButton(onClick={scope.launch{runCatchingActive{repository.rejectCall(ringing.id)};incomingCall=null}}){Text("Decline")}})
   val availableUpdate=updateInfo;if(authChecked&&activeCall==null&&incomingCall==null&&availableUpdate!=null)AlertDialog(onDismissRequest={updateInfo=null},title={Text("TwoShare update available")},text={Text("A newer version of TwoShare (${availableUpdate.latestVersionName}) is available. TwoShare will download the update and open the Android installer.")},confirmButton={TextButton(onClick={runCatchingActive{downloadAndInstallUpdate(this@MainActivity,availableUpdate.downloadUrl,availableUpdate.latestVersionName)};updateInfo=null}){Text("Update now")}},dismissButton={TextButton(onClick={updateInfo=null}){Text("Later")}})
   if(authChecked&&signedIn&&!biometricRequired&&showWhatsNew&&activeCall==null&&incomingCall==null)WhatsNewDialog{markWhatsNewSeen(context,BuildConfig.VERSION_CODE);showWhatsNew=false}
  }
 }}
 private fun requestBiometricUnlock(onSuccess:()->Unit,onError:(String)->Unit){val executor=ContextCompat.getMainExecutor(this);val prompt=BiometricPrompt(this,executor,object:BiometricPrompt.AuthenticationCallback(){override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult){super.onAuthenticationSucceeded(result);onSuccess()};override fun onAuthenticationError(errorCode:Int,errString:CharSequence){super.onAuthenticationError(errorCode,errString);onError(if(errorCode in listOf(BiometricPrompt.ERROR_NEGATIVE_BUTTON,BiometricPrompt.ERROR_USER_CANCELED,BiometricPrompt.ERROR_CANCELED))"Biometric unlock cancelled." else errString.toString().ifBlank{"Biometric unlock failed."})};override fun onAuthenticationFailed(){super.onAuthenticationFailed();onError("Fingerprint or face not recognized. Try again.")}});prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock TwoShare").setSubtitle("Use your fingerprint or face").setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK).setNegativeButtonText("Use account login").build())}
 companion object{private const val BIOMETRIC_GRACE_MS=5*60*1000L}
}
@Composable private fun BiometricUnlockScreen(error:String?,onUnlock:()->Unit,onUseAccountLogin:()->Unit){
 val loginPhoto=remember{listOf(R.drawable.login_photo_01,R.drawable.login_photo_02,R.drawable.login_photo_03,R.drawable.login_photo_04,R.drawable.login_photo_05,R.drawable.login_photo_06,R.drawable.login_photo_07,R.drawable.login_photo_08,R.drawable.login_photo_09,R.drawable.login_photo_10).random()}
 Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)){
  AuthPhotoBackdrop(loginPhoto)
  Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal=24.dp,vertical=28.dp),horizontalAlignment=Alignment.CenterHorizontally){
   AuthLogo(76.dp);Spacer(Modifier.height(14.dp));Text("TwoShare is locked",style=MaterialTheme.typography.headlineSmall);Spacer(Modifier.height(4.dp));Text("Unlock this trusted phone with fingerprint or face.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center);Spacer(Modifier.height(20.dp))
   Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(28.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface.copy(alpha=.97f)),elevation=CardDefaults.cardElevation(defaultElevation=8.dp)){Column(Modifier.fillMaxWidth().padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally){
    error?.let{Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.errorContainer){Text(it,Modifier.padding(horizontal=14.dp,vertical=10.dp),color=MaterialTheme.colorScheme.onErrorContainer,style=MaterialTheme.typography.bodyMedium,textAlign=TextAlign.Center)};Spacer(Modifier.height(14.dp))}
    Button(onClick=onUnlock,modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(16.dp)){Icon(Icons.Default.Fingerprint,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("Unlock")};Spacer(Modifier.height(4.dp))
    TextButton(onClick=onUseAccountLogin,modifier=Modifier.fillMaxWidth()){Text("Use account login instead")}
   }}
  }
 }
}
@Composable private fun TwoShareStartupScreen(){
 val colors=MaterialTheme.colorScheme
 Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.primaryContainer.copy(alpha=.45f),colors.background,colors.background))),contentAlignment=Alignment.Center){
  Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
   AuthLogo(128.dp);Spacer(Modifier.height(22.dp));Text("TwoShare",style=MaterialTheme.typography.headlineLarge);Spacer(Modifier.height(4.dp));Text("Private sharing for two",style=MaterialTheme.typography.bodyMedium,color=colors.onSurfaceVariant)
   Spacer(Modifier.height(28.dp));CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp,color=colors.primary)
  }
 }
}
