package com.twoshare.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.twoshare.app.data.AppVersionInfo
import com.twoshare.app.data.AppVersionRepository
import com.twoshare.app.data.AuthManager
import com.twoshare.app.data.SupabaseProvider
import com.twoshare.app.data.TwoShareRepository
import com.twoshare.app.model.CallRow
import com.twoshare.app.ui.CallScreen
import com.twoshare.app.ui.FeedScreen
import com.twoshare.app.ui.LoginScreen
import com.twoshare.app.ui.MessengerScreen
import com.twoshare.app.ui.theme.TwoShareTheme
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        SupabaseProvider.client.handleDeeplinks(intent)

        setContent {
            TwoShareTheme {
                val repository = remember { TwoShareRepository() }
                val authManager = remember { AuthManager() }
                val versionRepository = remember { AppVersionRepository() }
                val scope = rememberCoroutineScope()

                var authChecked by remember { mutableStateOf(false) }
                var signedIn by remember { mutableStateOf(false) }
                var biometricRequired by remember { mutableStateOf(false) }
                var biometricError by remember { mutableStateOf<String?>(null) }
                var biometricPromptVersion by remember { mutableIntStateOf(0) }
                var showMessenger by remember { mutableStateOf(false) }
                var activeCall by remember { mutableStateOf<CallRow?>(null) }
                var activeCallIncoming by remember { mutableStateOf(false) }
                var incomingCall by remember { mutableStateOf<CallRow?>(null) }
                var updateInfo by remember { mutableStateOf<AppVersionInfo?>(null) }

                LaunchedEffect(Unit) {
                    // Version checking is deliberately non-blocking. If the network or version
                    // endpoint is temporarily unavailable, TwoShare still opens normally.
                    runCatching { versionRepository.latestVersion() }
                        .getOrNull()
                        ?.takeIf { it.latestVersionCode > BuildConfig.VERSION_CODE }
                        ?.let { updateInfo = it }
                }

                LaunchedEffect(Unit) {
                    val startedAt = System.currentTimeMillis()
                    val restored = authManager.restoreTrustedSession(this@MainActivity)

                    if (restored && authManager.isBiometricEnabled(this@MainActivity)) {
                        if (authManager.canUseBiometrics(this@MainActivity)) {
                            biometricRequired = true
                        } else {
                            runCatching { repository.signOut() }
                            signedIn = false
                        }
                    } else {
                        signedIn = restored
                    }

                    val remainingSplash = 700L - (System.currentTimeMillis() - startedAt)
                    if (remainingSplash > 0) delay(remainingSplash)
                    authChecked = true
                }

                // Keep Compose navigation in sync with the actual Supabase session. Previously the
                // local signedIn flag could remain true after Auth had lost the session, leaving the
                // feed visible while repository calls reported "Not signed in".
                LaunchedEffect(Unit) {
                    SupabaseProvider.client.auth.sessionStatus.collect { status ->
                        if (!authChecked) return@collect
                        if (status is SessionStatus.NotAuthenticated && signedIn && !biometricRequired) {
                            showMessenger = false
                            activeCall = null
                            activeCallIncoming = false
                            incomingCall = null
                            signedIn = false
                        }
                    }
                }

                LaunchedEffect(authChecked, biometricRequired, biometricPromptVersion) {
                    if (!authChecked || !biometricRequired) return@LaunchedEffect
                    requestBiometricUnlock(
                        onSuccess = {
                            biometricError = null
                            biometricRequired = false
                            signedIn = true
                        },
                        onError = { message ->
                            biometricError = message
                        }
                    )
                }

                LaunchedEffect(signedIn, activeCall) {
                    if (!authChecked || !signedIn || activeCall != null) return@LaunchedEffect
                    while (isActive && signedIn && activeCall == null) {
                        val currentIncoming = incomingCall
                        if (currentIncoming == null) {
                            incomingCall = runCatching { repository.findIncomingCall() }.getOrNull()
                        } else {
                            val fresh = runCatching { repository.getCall(currentIncoming.id) }.getOrNull()
                            incomingCall = fresh?.takeIf { it.status == "ringing" }
                        }
                        delay(1_500)
                    }
                }

                when {
                    !authChecked -> TwoShareStartupScreen()

                    biometricRequired -> BiometricUnlockScreen(
                        error = biometricError,
                        onUnlock = {
                            biometricError = null
                            biometricPromptVersion++
                        },
                        onUseAccountLogin = {
                            scope.launch {
                                runCatching { repository.signOut() }
                                biometricRequired = false
                                biometricError = null
                                signedIn = false
                            }
                        }
                    )

                    signedIn -> {
                        val call = activeCall
                        if (call != null) {
                            CallScreen(
                                repository = repository,
                                initialCall = call,
                                incoming = activeCallIncoming,
                                onFinished = {
                                    activeCall = null
                                    activeCallIncoming = false
                                }
                            )
                        } else if (showMessenger) {
                            MessengerScreen(
                                repository = repository,
                                onBack = { showMessenger = false },
                                onStartCall = { startedCall, incoming ->
                                    incomingCall = null
                                    activeCallIncoming = incoming
                                    activeCall = startedCall
                                }
                            )
                        } else {
                            FeedScreen(
                                repository = repository,
                                authManager = authManager,
                                onOpenMessenger = { showMessenger = true },
                                onSignedOut = {
                                    showMessenger = false
                                    activeCall = null
                                    incomingCall = null
                                    biometricRequired = false
                                    signedIn = false
                                }
                            )
                        }
                    }

                    else -> LoginScreen(
                        authManager = authManager,
                        onSignedIn = {
                            showMessenger = false
                            activeCall = null
                            incomingCall = null
                            biometricRequired = false
                            signedIn = true
                        }
                    )
                }

                val ringing = incomingCall
                if (authChecked && signedIn && activeCall == null && ringing != null) {
                    AlertDialog(
                        onDismissRequest = { },
                        title = { Text("Incoming TwoLink") },
                        text = {
                            Text(
                                if (ringing.isVideo) {
                                    "Your TwoShare partner is starting a video TwoLink."
                                } else {
                                    "Your TwoShare partner is starting a voice TwoLink."
                                }
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                incomingCall = null
                                activeCallIncoming = true
                                activeCall = ringing
                            }) {
                                Text("Answer")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                scope.launch {
                                    runCatching { repository.rejectCall(ringing.id) }
                                    incomingCall = null
                                }
                            }) {
                                Text("Decline")
                            }
                        }
                    )
                }

                val availableUpdate = updateInfo
                if (authChecked && activeCall == null && incomingCall == null && availableUpdate != null) {
                    AlertDialog(
                        onDismissRequest = { updateInfo = null },
                        title = { Text("TwoShare update available") },
                        text = {
                            Text(
                                "A newer version of TwoShare (${availableUpdate.latestVersionName}) is available. " +
                                    "Download the latest version here."
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    runCatching {
                                        startActivity(
                                            Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse(availableUpdate.downloadUrl)
                                            )
                                        )
                                    }
                                    updateInfo = null
                                }
                            ) {
                                Text("Download latest version")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { updateInfo = null }) {
                                Text("Later")
                            }
                        }
                    )
                }
            }
        }
    }

    private fun requestBiometricUnlock(
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onError(
                        when (errorCode) {
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_CANCELED -> "Biometric unlock cancelled."
                            else -> errString.toString().ifBlank { "Biometric unlock failed." }
                        }
                    )
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onError("Fingerprint or face not recognized. Try again.")
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock TwoShare")
            .setSubtitle("Use your fingerprint or face")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .setNegativeButtonText("Use account login")
            .build()

        prompt.authenticate(promptInfo)
    }
}

@Composable
private fun BiometricUnlockScreen(
    error: String?,
    onUnlock: () -> Unit,
    onUseAccountLogin: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(R.drawable.twoshare_icon),
                contentDescription = "TwoShare logo",
                modifier = Modifier.size(120.dp)
            )
            Spacer(Modifier.height(18.dp))
            Text("TwoShare locked", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text("Unlock this trusted phone with fingerprint or face.")
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(20.dp))
            Button(onClick = onUnlock) {
                Text("Unlock with biometrics")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onUseAccountLogin) {
                Text("Use account login")
            }
        }
    }
}

@Composable
private fun TwoShareStartupScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(R.drawable.twoshare_icon),
                contentDescription = "TwoShare logo",
                modifier = Modifier.size(144.dp)
            )
            Spacer(Modifier.height(18.dp))
            Text("TwoShare", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "Private sharing for two",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
