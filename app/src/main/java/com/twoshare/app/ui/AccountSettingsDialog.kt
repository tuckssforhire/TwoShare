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
import kotlinx.coroutines.launch

@Composable
fun AccountSettingsDialog(
    authManager: AuthManager,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var biometricEnabled by remember { mutableStateOf(authManager.isBiometricEnabled(context)) }
    val biometricAvailable = remember { authManager.canUseBiometrics(context) }
    var savingPassword by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Account & security") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Change password", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = currentPassword,
                    onValueChange = { currentPassword = it; error = null; message = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Current password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
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

                Button(
                    onClick = {
                        scope.launch {
                            savingPassword = true
                            error = null
                            message = null
                            runCatching {
                                require(newPassword == confirmPassword) { "New passwords do not match." }
                                authManager.changePassword(currentPassword, newPassword)
                            }.onSuccess {
                                currentPassword = ""
                                newPassword = ""
                                confirmPassword = ""
                                message = "Password changed."
                            }.onFailure {
                                val raw = it.localizedMessage.orEmpty()
                                error = when {
                                    "invalid" in raw.lowercase() && "password" in raw.lowercase() ->
                                        "Current password is incorrect."
                                    raw.isBlank() -> "Unable to change password."
                                    else -> raw.lineSequence().firstOrNull()?.take(160)
                                        ?: "Unable to change password."
                                }
                            }
                            savingPassword = false
                        }
                    },
                    enabled = !savingPassword && currentPassword.isNotBlank() &&
                        newPassword.length >= 8 && confirmPassword.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (savingPassword) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Change password")
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
