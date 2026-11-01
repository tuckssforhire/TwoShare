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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.twoshare.app.data.*
import com.twoshare.app.model.CallRow
import com.twoshare.app.push.PushNotificationManager
import com.twoshare.app.ui.CallScreen
import com.twoshare.app.ui.FeedScreen
import com.twoshare.app.ui.LoginScreen
import com.twoshare.app.ui.MessengerScreenV2
import com.twoshare.app.ui.theme.*
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
 private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){}
 private var backgroundedAt:Long=0L
 private var biometricLockEnabled=false
 private var requestBiometricAfterResume=false
 override fun onStop(){super.onStop();if(!isChangingConfigurations&&biometricLockEnabled)backgroundedAt=System.currentTimeMillis()}
 override fun onStart(){super.onStart();if(biometricLockEnabled&&backgroundedAt>0L&&System.currentTimeMillis()-backgroundedAt>=BIOMETRIC_GRACE_MS)requestBiometricAfterResume=true;backgroundedAt=0L}
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);SupabaseProvider.client.handleDeeplinks(intent);setContent{
  val context=LocalContext.current;var themeUserId by remember{mutableStateOf(SupabaseProvider.client.auth.currentUserOrNull()?.id)};var selectedTheme by remember{mutableStateOf(loadTheme(context,themeUserId))}
  TwoShareTheme(theme=selectedTheme){
   val repository=remember{TwoShareRepository()};val chatFeatures=remember(repository){ChatFeaturesRepository(repository)};val authManager=remember{AuthManager()};val versionRepository=remember{AppVersionRepository()};val scope=rememberCoroutineScope()
   var authChecked by remember{mutableStateOf(false)};var signedIn by remember{mutableStateOf(false)};var biometricRequired by remember{mutableStateOf(false)};var biometricError by remember{mutableStateOf<String?>(null)};var biometricPromptVersion by remember{mutableIntStateOf(0)}
   var showMessenger by remember{mutableStateOf(intent.getBooleanExtra("open_messenger",false))};var activeCall by remember{mutableStateOf<CallRow?>(null)};var activeCallIncoming by remember{mutableStateOf(false)};var incomingCall by remember{mutableStateOf<CallRow?>(null)};var updateInfo by remember{mutableStateOf<AppVersionInfo?>(null)};var showExitConfirmation by remember{mutableStateOf(false)}
   fun refreshThemeForCurrentUser(){themeUserId=SupabaseProvider.client.auth.currentUserOrNull()?.id;selectedTheme=loadTheme(context,themeUserId)}
   fun chooseTheme(theme:String){selectedTheme=theme;saveTheme(context,themeUserId,theme)}
   BackHandler(enabled=authChecked&&signedIn&&!biometricRequired&&activeCall==null){if(showMessenger)showMessenger=false else showExitConfirmation=true}
   LaunchedEffect(Unit){runCatching{versionRepository.latestVersion()}.getOrNull()?.takeIf{it.latestVersionCode>BuildConfig.VERSION_CODE}?.let{updateInfo=it}}
   LaunchedEffect(Unit){val startedAt=System.currentTimeMillis();val restored=authManager.restoreTrustedSession(this@MainActivity);biometricLockEnabled=restored&&authManager.isBiometricEnabled(this@MainActivity);if(restored&&biometricLockEnabled){if(authManager.canUseBiometrics(this@MainActivity))biometricRequired=true else{runCatching{repository.signOut()};signedIn=false}}else signedIn=restored;if(restored)refreshThemeForCurrentUser();val remainingSplash=700L-(System.currentTimeMillis()-startedAt);if(remainingSplash>0)delay(remainingSplash);authChecked=true}
   DisposableEffect(Unit){val observer=androidx.lifecycle.LifecycleEventObserver{_,event->if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME&&requestBiometricAfterResume&&signedIn&&authManager.isBiometricEnabled(this@MainActivity)){requestBiometricAfterResume=false;biometricRequired=true}};lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}}
   LaunchedEffect(signedIn,biometricRequired){biometricLockEnabled=signedIn&&authManager.isBiometricEnabled(this@MainActivity);if(signedIn&&!biometricRequired){refreshThemeForCurrentUser();if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this@MainActivity,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);runCatching{PushNotificationManager.registerCurrentDevice(this@MainActivity)}}}
   LaunchedEffect(Unit){SupabaseProvider.client.auth.sessionStatus.collect{status->if(!authChecked)return@collect;when(status){is SessionStatus.Authenticated->Unit;SessionStatus.Initializing,is SessionStatus.RefreshFailure->Unit;is SessionStatus.NotAuthenticated->{if(!signedIn||biometricRequired)return@collect;delay(750);if(SupabaseProvider.client.auth.currentUserOrNull()==null){showMessenger=false;activeCall=null;activeCallIncoming=false;incomingCall=null;signedIn=false}}}}}
   LaunchedEffect(authChecked,biometricRequired,biometricPromptVersion){if(!authChecked||!biometricRequired)return@LaunchedEffect;requestBiometricUnlock({biometricError=null;biometricRequired=false;signedIn=true},{biometricError=it})}
   LaunchedEffect(signedIn,activeCall){if(!authChecked||!signedIn||activeCall!=null)return@LaunchedEffect;while(isActive&&signedIn&&activeCall==null){runCatching{chatFeatures.markIncomingDelivered()};val currentIncoming=incomingCall;if(currentIncoming==null)incomingCall=runCatching{repository.findIncomingCall()}.getOrNull() else incomingCall=runCatching{repository.getCall(currentIncoming.id)}.getOrNull()?.takeIf{it.status=="ringing"};delay(1_500)}}
   when{!authChecked->TwoShareStartupScreen();biometricRequired->BiometricUnlockScreen(biometricError,{biometricError=null;biometricPromptVersion++},{scope.launch{runCatching{PushNotificationManager.unregisterCurrentDevice(this@MainActivity)};runCatching{repository.signOut()};biometricLockEnabled=false;biometricRequired=false;biometricError=null;signedIn=false}});signedIn->{val call=activeCall;if(call!=null)CallScreen(repository,call,activeCallIncoming){activeCall=null;activeCallIncoming=false}else if(showMessenger)MessengerScreenV2(repository,{showMessenger=false}){startedCall,incoming->incomingCall=null;activeCallIncoming=incoming;activeCall=startedCall}else FeedScreen(repository,authManager,selectedTheme,::chooseTheme,{showMessenger=true}){scope.launch{runCatching{PushNotificationManager.unregisterCurrentDevice(this@MainActivity)}};showMessenger=false;activeCall=null;incomingCall=null;biometricLockEnabled=false;biometricRequired=false;signedIn=false}};else->LoginScreen(authManager){showMessenger=false;activeCall=null;incomingCall=null;biometricRequired=false;signedIn=true;biometricLockEnabled=authManager.isBiometricEnabled(this@MainActivity);refreshThemeForCurrentUser()}}
   if(showExitConfirmation)AlertDialog(onDismissRequest={showExitConfirmation=false},title={Text("Exit TwoShare?")},text={Text("Do you want to exit the app?")},confirmButton={TextButton(onClick={showExitConfirmation=false;finish()}){Text("Exit")}},dismissButton={TextButton(onClick={showExitConfirmation=false}){Text("Cancel")}})
   val ringing=incomingCall;if(authChecked&&signedIn&&activeCall==null&&ringing!=null)AlertDialog(onDismissRequest={},title={Text("Incoming TwoLink")},text={Text(if(ringing.isVideo)"Your TwoShare partner is starting a video TwoLink." else "Your TwoShare partner is starting a voice TwoLink.")},confirmButton={TextButton(onClick={incomingCall=null;activeCallIncoming=true;activeCall=ringing}){Text("Answer")}},dismissButton={TextButton(onClick={scope.launch{runCatching{repository.rejectCall(ringing.id)};incomingCall=null}}){Text("Decline")}})
   val availableUpdate=updateInfo;if(authChecked&&activeCall==null&&incomingCall==null&&availableUpdate!=null)AlertDialog(onDismissRequest={updateInfo=null},title={Text("TwoShare update available")},text={Text("A newer version of TwoShare (${availableUpdate.latestVersionName}) is available. Download the latest version here.")},confirmButton={TextButton(onClick={runCatching{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(availableUpdate.downloadUrl)))};updateInfo=null}){Text("Download latest version")}},dismissButton={TextButton(onClick={updateInfo=null}){Text("Later")}})
  }
 }}
 private fun requestBiometricUnlock(onSuccess:()->Unit,onError:(String)->Unit){val executor=ContextCompat.getMainExecutor(this);val prompt=BiometricPrompt(this,executor,object:BiometricPrompt.AuthenticationCallback(){override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult){super.onAuthenticationSucceeded(result);onSuccess()};override fun onAuthenticationError(errorCode:Int,errString:CharSequence){super.onAuthenticationError(errorCode,errString);onError(if(errorCode in listOf(BiometricPrompt.ERROR_NEGATIVE_BUTTON,BiometricPrompt.ERROR_USER_CANCELED,BiometricPrompt.ERROR_CANCELED))"Biometric unlock cancelled." else errString.toString().ifBlank{"Biometric unlock failed."})};override fun onAuthenticationFailed(){super.onAuthenticationFailed();onError("Fingerprint or face not recognized. Try again.")}});prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock TwoShare").setSubtitle("Use your fingerprint or face").setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK).setNegativeButtonText("Use account login").build())}
 companion object{private const val BIOMETRIC_GRACE_MS=5*60*1000L}
}
@Composable private fun BiometricUnlockScreen(error:String?,onUnlock:()->Unit,onUseAccountLogin:()->Unit){Box(Modifier.fillMaxSize().padding(28.dp),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Image(painterResource(R.drawable.twoshare_icon),"TwoShare logo",Modifier.size(120.dp));Spacer(Modifier.height(18.dp));Text("TwoShare locked",style=MaterialTheme.typography.headlineSmall);Spacer(Modifier.height(6.dp));Text("Unlock this trusted phone with fingerprint or face.");error?.let{Spacer(Modifier.height(12.dp));Text(it,color=MaterialTheme.colorScheme.error)};Spacer(Modifier.height(20.dp));Button(onClick=onUnlock){Text("Unlock with biometrics")};Spacer(Modifier.height(8.dp));OutlinedButton(onClick=onUseAccountLogin){Text("Use account login")}}}}
@Composable private fun TwoShareStartupScreen(){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Image(painterResource(R.drawable.twoshare_icon),"TwoShare logo",Modifier.size(144.dp));Spacer(Modifier.height(18.dp));Text("TwoShare",style=MaterialTheme.typography.headlineLarge);Spacer(Modifier.height(6.dp));Text("Private sharing for two",style=MaterialTheme.typography.bodyMedium)}}}
