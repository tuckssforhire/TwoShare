package com.twoshare.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.twoshare.app.data.AuthManager
import com.twoshare.app.data.PasswordSignInResult
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    authManager: AuthManager,
    onSignedIn: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var needsEmailCode by remember { mutableStateOf(false) }
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
                Text("Private sharing and messenger for two", style = MaterialTheme.typography.bodyMedium)
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
                    enabled = !needsEmailCode && !loading,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )

                if (!needsEmailCode) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            error = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Password") },
                        singleLine = true,
                        enabled = !loading,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                } else {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = {
                            code = it.filter(Char::isDigit).take(8)
                            error = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Email verification code") },
                        singleLine = true,
                        enabled = !loading,
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

                if (!needsEmailCode) {
                    Button(
                        onClick = {
                            scope.launch {
                                loading = true
                                error = null
                                info = null
                                runCatching {
                                    authManager.signInWithPassword(
                                        context = context,
                                        email = email,
                                        password = password
                                    )
                                }.onSuccess { result ->
                                    when (result) {
                                        PasswordSignInResult.TRUSTED_DEVICE -> onSignedIn()
                                        PasswordSignInResult.EMAIL_CODE_REQUIRED -> {
                                            password = ""
                                            needsEmailCode = true
                                            info = "New device detected. Enter the verification code sent to your email. You will not need the code again on this device."
                                        }
                                    }
                                }.onFailure {
                                    error = it.localizedMessage ?: "Unable to sign in"
                                }
                                loading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading && email.isNotBlank() && password.isNotBlank()
                    ) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Sign in")
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            scope.launch {
                                loading = true
                                error = null
                                runCatching {
                                    authManager.verifyNewDevice(
                                        context = context,
                                        email = email,
                                        code = code
                                    )
                                }.onSuccess { onSignedIn() }
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
                            Text("Verify this device")
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            needsEmailCode = false
                            code = ""
                            password = ""
                            info = null
                            error = null
                        },
                        enabled = !loading
                    ) {
                        Text("Back to password")
                    }
                }
            }
        }
    }
}
