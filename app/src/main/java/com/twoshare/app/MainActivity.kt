package com.twoshare.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.twoshare.app.data.AuthManager
import com.twoshare.app.data.SupabaseProvider
import com.twoshare.app.data.TwoShareRepository
import com.twoshare.app.model.CallRow
import com.twoshare.app.ui.CallScreen
import com.twoshare.app.ui.FeedScreen
import com.twoshare.app.ui.LoginScreen
import com.twoshare.app.ui.MessengerScreen
import com.twoshare.app.ui.theme.TwoShareTheme
import io.github.jan.supabase.auth.handleDeeplinks
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        SupabaseProvider.client.handleDeeplinks(intent)

        setContent {
            TwoShareTheme {
                val repository = remember { TwoShareRepository() }
                val authManager = remember { AuthManager() }
                val scope = rememberCoroutineScope()

                var authChecked by remember { mutableStateOf(false) }
                var signedIn by remember { mutableStateOf(false) }
                var showMessenger by remember { mutableStateOf(false) }
                var activeCall by remember { mutableStateOf<CallRow?>(null) }
                var activeCallIncoming by remember { mutableStateOf(false) }
                var incomingCall by remember { mutableStateOf<CallRow?>(null) }

                LaunchedEffect(Unit) {
                    val startedAt = System.currentTimeMillis()
                    val restored = authManager.restoreTrustedSession(this@MainActivity)
                    val remainingSplash = 700L - (System.currentTimeMillis() - startedAt)
                    if (remainingSplash > 0) delay(remainingSplash)
                    signedIn = restored
                    authChecked = true
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

                if (!authChecked) {
                    TwoShareStartupScreen()
                } else if (signedIn) {
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
                            onOpenMessenger = { showMessenger = true },
                            onSignedOut = {
                                showMessenger = false
                                activeCall = null
                                incomingCall = null
                                signedIn = false
                            }
                        )
                    }
                } else {
                    LoginScreen(
                        authManager = authManager,
                        onSignedIn = {
                            showMessenger = false
                            activeCall = null
                            incomingCall = null
                            signedIn = true
                        }
                    )
                }

                val ringing = incomingCall
                if (authChecked && signedIn && activeCall == null && ringing != null) {
                    AlertDialog(
                        onDismissRequest = { },
                        title = {
                            Text(if (ringing.isVideo) "Incoming video call" else "Incoming voice call")
                        },
                        text = { Text("Your TwoShare partner is calling.") },
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
