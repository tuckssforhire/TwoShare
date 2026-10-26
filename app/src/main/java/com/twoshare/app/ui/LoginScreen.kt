package com.twoshare.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.twoshare.app.data.TwoShareRepository
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    repository: TwoShareRepository,
    onSignedIn: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var emailSent by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("TwoShare", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(8.dp))
                Text("Private photo sharing for two", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(28.dp))

                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Email") },
                    singleLine = true,
                    enabled = !emailSent && !loading,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )

                if (emailSent) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = {
                            code = it.filter(Char::isDigit).take(8)
                            error = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Email code (if shown)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                }

                info?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }

                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }

                Spacer(Modifier.height(20.dp))

                if (!emailSent) {
                    Button(
                        onClick = {
                            scope.launch {
                                loading = true
                                error = null
                                info = null
                                runCatching { repository.sendSignInEmail(email) }
                                    .onSuccess {
                                        emailSent = true
                                        info = "Check your email. If Supabase shows a sign-in link, tap it. If it shows a code, enter the code here."
                                    }
                                    .onFailure {
                                        error = it.localizedMessage ?: "Unable to send sign-in email"
                                    }
                                loading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading && email.isNotBlank()
                    ) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Send sign-in email")
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            scope.launch {
                                loading = true
                                error = null
                                runCatching { repository.verifyEmailCode(email, code) }
                                    .onSuccess { onSignedIn() }
                                    .onFailure {
                                        error = it.localizedMessage ?: "Verification failed"
                                    }
                                loading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading && code.isNotBlank()
                    ) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Verify code")
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            emailSent = false
                            code = ""
                            info = null
                            error = null
                        },
                        enabled = !loading
                    ) {
                        Text("Use another email")
                    }
                }
            }
        }
    }
}
