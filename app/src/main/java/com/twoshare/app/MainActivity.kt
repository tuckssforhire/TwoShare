package com.twoshare.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
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
                val scope = rememberCoroutineScope()
                var signedIn by remember { mutableStateOf(repository.isSignedIn()) }
                var showMessenger by remember { mutableStateOf(false) }
                var activeCall by remember { mutableStateOf<CallRow?>(null) }
                var activeCallIncoming by remember { mutableStateOf(false) }
                var incomingCall by remember { mutableStateOf<CallRow?>(null) }

                LaunchedEffect(signedIn, activeCall) {
                    if (!signedIn || activeCall != null) return@LaunchedEffect
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

                if (signedIn) {
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
                        repository = repository,
                        onSignedIn = {
                            showMessenger = false
                            activeCall = null
                            incomingCall = null
                            signedIn = true
                        }
                    )
                }

                val ringing = incomingCall
                if (signedIn && activeCall == null && ringing != null) {
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
