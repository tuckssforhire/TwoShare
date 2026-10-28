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

private enum class VerificationMode {
    NEW_DEVICE,
    SET_PASSWORD
}

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
    var verificationMode by remember { mutableStateOf<VerificationMode?>(null) }
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
                Image(
                    painter = painterResource(R.drawable.twoshare_icon),
                    contentDescription = "TwoShare logo",
                    modifier = Modifier.size(112.dp)
                )
                Spacer(Modifier.height(14.dp))
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
                    enabled = verificationMode == null && !loading,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )

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
                    enabled = verificationMode == null && !loading,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )

                if (verificationMode != null) {
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

                if (verificationMode == null) {
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
                                            verificationMode = VerificationMode.NEW_DEVICE
                                            info = "New device detected. Enter the code sent to your email. After verification, this phone will be trusted."
                                        }
                                    }
                                }.onFailure {
                                    error = friendlyAuthError(it)
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

                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                loading = true
                                error = null
                                info = null
                                runCatching {
                                    authManager.startEmailCodeSignIn(email)
                                }.onSuccess {
                                    verificationMode = VerificationMode.NEW_DEVICE
                                    code = ""
                                    info = "We sent a sign-in code to your email. Enter it below to sign in and trust this phone."
                                }.onFailure {
                                    error = friendlyAuthError(it)
                                }
                                loading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading && email.isNotBlank()
                    ) {
                        Text("Sign in with email code")
                    }

                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = {
                            scope.launch {
                                loading = true
                                error = null
                                info = null
                                runCatching {
                                    require(password.length >= 6) {
                                        "Type the new password you want to use first."
                                    }
                                    authManager.startPasswordSetup(email)
                                }.onSuccess {
                                    verificationMode = VerificationMode.SET_PASSWORD
                                    code = ""
                                    info = "Enter the email code to save this new password and trust this phone."
                                }.onFailure {
                                    error = friendlyAuthError(it)
                                }
                                loading = false
                            }
                        },
                        enabled = !loading && email.isNotBlank() && password.length >= 6
                    ) {
                        Text("Set / reset password with email code")
                    }
                } else {
                    Button(
                        onClick = {
                            val mode = verificationMode ?: return@Button
                            scope.launch {
                                loading = true
                                error = null
                                runCatching {
                                    when (mode) {
                                        VerificationMode.NEW_DEVICE -> {
                                            authManager.verifyNewDevice(
                                                context = context,
                                                email = email,
                                                code = code
                                            )
                                        }
                                        VerificationMode.SET_PASSWORD -> {
                                            authManager.verifyAndSetPassword(
                                                context = context,
                                                email = email,
                                                code = code,
                                                newPassword = password
                                            )
                                        }
                                    }
                                }.onSuccess {
                                    onSignedIn()
                                }.onFailure {
                                    error = friendlyAuthError(it)
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
                            Text("Verify email code")
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            verificationMode = null
                            code = ""
                            info = null
                            error = null
                        },
                        enabled = !loading
                    ) {
                        Text("Back to login")
                    }
                }
            }
        }
    }
}

private fun friendlyAuthError(throwable: Throwable): String {
    val raw = throwable.localizedMessage.orEmpty()
    val lower = raw.lowercase()
    return when {
        "invalid_credentials" in lower || "invalid login credentials" in lower ->
            "Incorrect email or password. Try again, sign in with an email code, or reset your password."

        "rate limit" in lower || "over_email_send_rate_limit" in lower ->
            "Too many email requests. Please try again shortly."

        "expired" in lower && ("otp" in lower || "token" in lower || "code" in lower) ->
            "That email code has expired. Go back and request a new code."

        "invalid" in lower && ("otp" in lower || "token" in lower || "code" in lower) ->
            "That email code is invalid. Check the latest email and try again."

        raw.isBlank() -> "Unable to sign in. Please try again."
        else -> raw.lineSequence().firstOrNull()?.take(180) ?: "Unable to sign in. Please try again."
    }
}
