package com.twoshare.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.twoshare.app.data.AuthManager
import kotlinx.coroutines.launch

@Composable
fun AccountSettingsDialog(
    authManager: AuthManager,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var displayName by remember { mutableStateOf("") }
    var savedDisplayName by remember { mutableStateOf("") }
    var profileLoading by remember { mutableStateOf(true) }

    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var verificationCode by remember { mutableStateOf("") }
    var verificationSent by remember { mutableStateOf(false) }
    var biometricEnabled by remember { mutableStateOf(authManager.isBiometricEnabled(context)) }
    val biometricAvailable = remember { authManager.canUseBiometrics(context) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { authManager.currentProfile() }
            .onSuccess {
                displayName = it.displayName
                savedDisplayName = it.displayName
            }
            .onFailure {
                error = it.localizedMessage ?: "Unable to load profile."
            }
        profileLoading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Account & security") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Profile", style = MaterialTheme.typography.titleMedium)
                Text(
                    "This is the name your TwoShare partner sees on posts and messages.",
                    style = MaterialTheme.typography.bodySmall
                )

                OutlinedTextField(
                    value = displayName,
                    onValueChange = {
                        if (it.length <= 60) displayName = it
                        error = null
                        message = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Display name") },
                    supportingText = { Text("${displayName.length}/60") },
                    singleLine = true,
                    enabled = !profileLoading && !busy
                )

                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            message = null
                            runCatching { authManager.updateDisplayName(displayName) }
                                .onSuccess {
                                    displayName = it.displayName
                                    savedDisplayName = it.displayName
                                    message = "Display name updated."
                                }
                                .onFailure {
                                    error = it.localizedMessage ?: "Unable to update display name."
                                }
                            busy = false
                        }
                    },
                    enabled = !busy && !profileLoading && displayName.trim().isNotEmpty() && displayName.trim() != savedDisplayName,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save display name")
                }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                Text("Change password", style = MaterialTheme.typography.titleMedium)
                Text(
                    "TwoShare verifies your account by email before changing the password.",
                    style = MaterialTheme.typography.bodySmall
                )

                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it; error = null; message = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("New password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )

                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it; error = null; message = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Confirm new password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )

                if (!verificationSent) {
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                error = null
                                message = null
                                runCatching {
                                    require(newPassword.length >= 8) {
                                        "New password must be at least 8 characters."
                                    }
                                    require(newPassword == confirmPassword) {
                                        "New passwords do not match."
                                    }
                                    authManager.startPasswordChangeVerification()
                                }.onSuccess {
                                    verificationSent = true
                                    message = "Verification code sent to your email."
                                }.onFailure {
                                    val raw = it.localizedMessage.orEmpty()
                                    error = raw.lineSequence().firstOrNull()?.take(180)
                                        ?.takeIf { text -> text.isNotBlank() }
                                        ?: "Unable to send verification code."
                                }
                                busy = false
                            }
                        },
                        enabled = !busy && newPassword.length >= 8 && confirmPassword.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Send verification code")
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = verificationCode,
                        onValueChange = { verificationCode = it.filter(Char::isDigit).take(8); error = null; message = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Email verification code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )

                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                error = null
                                message = null
                                runCatching {
                                    require(newPassword == confirmPassword) {
                                        "New passwords do not match."
                                    }
                                    authManager.verifyAndChangePassword(verificationCode, newPassword)
                                }.onSuccess {
                                    newPassword = ""
                                    confirmPassword = ""
                                    verificationCode = ""
                                    verificationSent = false
                                    message = "Password changed successfully. You can now use it for normal password sign-in."
                                }.onFailure {
                                    val raw = it.localizedMessage.orEmpty()
                                    val lower = raw.lowercase()
                                    error = when {
                                        "same password" in lower || "same_password" in lower || "different from the old password" in lower ->
                                            "That is already your current password. Choose a different password, or keep it and use it to sign in normally."
                                        "expired" in lower ->
                                            "The verification code expired. Send a new code and try again."
                                        "invalid" in lower || "otp" in lower || "reauthentication_not_valid" in lower ->
                                            "The verification code is incorrect or expired."
                                        raw.isBlank() -> "Unable to change password."
                                        else -> raw.lineSequence().firstOrNull()?.take(180)
                                            ?: "Unable to change password."
                                    }
                                }
                                busy = false
                            }
                        },
                        enabled = !busy && verificationCode.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Verify & change password")
                        }
                    }

                    TextButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                error = null
                                message = null
                                runCatching { authManager.startPasswordChangeVerification() }
                                    .onSuccess { message = "A new verification code was sent." }
                                    .onFailure { error = "Unable to resend verification code." }
                                busy = false
                            }
                        },
                        enabled = !busy
                    ) {
                        Text("Resend code")
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Biometric unlock", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (biometricAvailable) {
                                "Use fingerprint or face to unlock TwoShare on this trusted phone."
                            } else {
                                "No fingerprint or face authentication is enrolled on this phone."
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = biometricEnabled,
                        onCheckedChange = { enabled ->
                            error = null
                            message = null
                            runCatching {
                                authManager.setBiometricEnabled(context, enabled)
                            }.onSuccess {
                                biometricEnabled = enabled
                                message = if (enabled) {
                                    "Biometric unlock enabled."
                                } else {
                                    "Biometric unlock disabled."
                                }
                            }.onFailure {
                                error = it.localizedMessage ?: "Unable to change biometric setting."
                            }
                        },
                        enabled = biometricAvailable || biometricEnabled
                    )
                }

                message?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}
