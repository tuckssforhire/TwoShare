package com.twoshare.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.twoshare.app.data.SupabaseProvider
import com.twoshare.app.data.TwoShareRepository
import com.twoshare.app.ui.FeedScreen
import com.twoshare.app.ui.LoginScreen
import com.twoshare.app.ui.MessengerScreen
import com.twoshare.app.ui.theme.TwoShareTheme
import io.github.jan.supabase.auth.handleDeeplinks

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        SupabaseProvider.client.handleDeeplinks(intent)

        setContent {
            TwoShareTheme {
                val repository = remember { TwoShareRepository() }
                var signedIn by remember { mutableStateOf(repository.isSignedIn()) }
                var showMessenger by remember { mutableStateOf(false) }

                if (signedIn) {
                    if (showMessenger) {
                        MessengerScreen(
                            repository = repository,
                            onBack = { showMessenger = false }
                        )
                    } else {
                        FeedScreen(
                            repository = repository,
                            onOpenMessenger = { showMessenger = true },
                            onSignedOut = {
                                showMessenger = false
                                signedIn = false
                            }
                        )
                    }
                } else {
                    LoginScreen(
                        repository = repository,
                        onSignedIn = {
                            showMessenger = false
                            signedIn = true
                        }
                    )
                }
            }
        }
    }
}
