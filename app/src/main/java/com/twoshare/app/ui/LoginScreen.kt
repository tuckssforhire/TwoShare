package com.twoshare.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.twoshare.app.R
import com.twoshare.app.data.AuthManager
import com.twoshare.app.data.PasswordSignInResult
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(authManager: AuthManager, onSignedIn: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(authManager.rememberedEmail(context)) }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var verifyingEmail by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Unable to continue") },
            text = { Text(message) },
            confirmButton = {
                Button(onClick = { error = null }) { Text("OK") }
            }
        )
    }

    info?.let { message ->
        AlertDialog(
            onDismissRequest = { info = null },
            title = { Text(if (verifyingEmail) "Check your email" else "TwoShare") },
            text = { Text(message) },
            confirmButton = {
                Button(onClick = { info = null }) { Text("OK") }
            }
        )
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painter = painterResource(R.drawable.twoshare_icon), contentDescription = "TwoShare logo", modifier = Modifier.size(112.dp))
                Spacer(Modifier.height(14.dp))
                Text("TwoShare", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(8.dp))
                Text("Private sharing and messenger for two", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(28.dp))

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true,
                    enabled = !verifyingEmail && !loading,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )

                if (!verifyingEmail) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
                        enabled = !loading, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                } else {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = code, onValueChange = { code = it.filter(Char::isDigit).take(8) },
                        modifier = Modifier.fillMaxWidth(), label = { Text("Email verification code") }, singleLine = true,
                        enabled = !loading, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                }

                Spacer(Modifier.height(20.dp))

                if (!verifyingEmail) {
                    Button(
                        onClick = {
                            scope.launch {
                                loading = true; error = null; info = null
                                runCatching { authManager.signInWithPassword(context, email, password) }
                                    .onSuccess { result ->
                                        when (result) {
                                            PasswordSignInResult.TRUSTED_DEVICE -> onSignedIn()
                                            PasswordSignInResult.EMAIL_CODE_REQUIRED -> {
                                                verifyingEmail = true; code = ""
                                                info = "New device detected. Enter the code sent to your email. No password change is required."
                                            }
                                        }
                                    }.onFailure { error = friendlyAuthError(it) }
                                loading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(), enabled = !loading && email.isNotBlank() && password.isNotBlank()
                    ) {
                        if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Sign in")
                    }
                    if (authManager.canUseBiometrics(context)) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Tip: after signing in, enable Biometric unlock in Account & security. TwoShare will then show a fingerprint/face unlock button when you reopen the app.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                loading = true; error = null; info = null
                                runCatching { authManager.startEmailCodeSignIn(email) }
                                    .onSuccess {
                                        verifyingEmail = true; code = ""
                                        info = "We sent a sign-in code to your email. Verify it to sign in and trust this phone."
                                    }.onFailure { error = friendlyAuthError(it) }
                                loading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(), enabled = !loading && email.isNotBlank()
                    ) { Text("Sign in with email code") }
                } else {
                    Button(
                        onClick = {
                            scope.launch {
                                loading = true; error = null
                                runCatching { authManager.verifyNewDevice(context, email, code) }
                                    .onSuccess { onSignedIn() }.onFailure { error = friendlyAuthError(it) }
                                loading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(), enabled = !loading && code.isNotBlank()
                    ) {
                        if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Verify email code")
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { verifyingEmail = false; code = ""; info = null; error = null }, enabled = !loading) { Text("Back to login") }
                }
            }
        }
    }
}

private fun friendlyAuthError(throwable: Throwable): String {
    val raw = throwable.localizedMessage.orEmpty()
    val lower = raw.lowercase()
    return when {
        "invalid_credentials" in lower || "invalid login credentials" in lower -> "Incorrect email or password. Try again or sign in with an email code."
        "rate limit" in lower || "over_email_send_rate_limit" in lower -> "Too many email requests. Please try again shortly."
        "expired" in lower && ("otp" in lower || "token" in lower || "code" in lower) -> "That email code has expired. Go back and request a new code."
        "invalid" in lower && ("otp" in lower || "token" in lower || "code" in lower) -> "That email code is invalid. Check the latest email and try again."
        raw.isBlank() -> "Unable to sign in. Please try again."
        else -> raw.lineSequence().firstOrNull()?.take(180) ?: "Unable to sign in. Please try again."
    }
}
