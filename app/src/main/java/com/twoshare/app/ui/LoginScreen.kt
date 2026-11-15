package com.twoshare.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
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
    var passwordVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val loginPhoto = rememberLoginBackground()

    error?.let { message ->
        AlertDialog(onDismissRequest = { error = null }, title = { Text("Unable to continue") }, text = { Text(message) }, confirmButton = { Button(onClick = { error = null }) { Text("OK") } })
    }
    info?.let { message ->
        AlertDialog(onDismissRequest = { info = null }, title = { Text(if (verifyingEmail) "Check your email" else "TwoShare") }, text = { Text(message) }, confirmButton = { Button(onClick = { info = null }) { Text("OK") } })
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AuthPhotoBackdrop(loginPhoto)

        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).imePadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AuthLogo(72.dp)
            Spacer(Modifier.height(10.dp))
            Text("TwoShare", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Just us, all in one place.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .97f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(if (verifyingEmail) "Verify this phone" else "Welcome back", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (verifyingEmail) "Enter the code we emailed you." else "Sign in to your shared space.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(value = email, onValueChange = { email = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Email") }, leadingIcon = { Icon(Icons.Outlined.Email, contentDescription = null) }, shape = RoundedCornerShape(16.dp), singleLine = true, enabled = !verifyingEmail && !loading, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
                    Spacer(Modifier.height(10.dp))
                    if (!verifyingEmail) {
                        OutlinedTextField(value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Password") }, leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = if (passwordVisible) "Hide password" else "Show password")
                                }
                            },
                            shape = RoundedCornerShape(16.dp), singleLine = true, enabled = !loading,
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }))
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    loading = true; error = null; info = null
                                    runCatchingActive { authManager.signInWithPassword(context, email, password) }
                                        .onSuccess { result ->
                                            when (result) {
                                                PasswordSignInResult.TRUSTED_DEVICE -> onSignedIn()
                                                PasswordSignInResult.EMAIL_CODE_REQUIRED -> { verifyingEmail = true; code = ""; info = "New device detected. Enter the code sent to your email. No password change is required." }
                                            }
                                        }.onFailure { error = friendlyAuthError(it) }
                                    loading = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp), enabled = !loading && email.isNotBlank() && password.isNotBlank(), shape = RoundedCornerShape(16.dp)
                        ) { if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Sign in") }
                        if (authManager.canUseBiometrics(context)) {
                            Spacer(Modifier.height(8.dp))
                            Text("Biometric unlock can be enabled in Account & security after signing in.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    loading = true; error = null; info = null
                                    runCatchingActive { authManager.startEmailCodeSignIn(email) }
                                        .onSuccess { verifyingEmail = true; code = ""; info = "We sent a sign-in code to your email. Verify it to sign in and trust this phone." }
                                        .onFailure { error = friendlyAuthError(it) }
                                    loading = false
                                }
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally), enabled = !loading && email.isNotBlank()
                        ) { Text("Sign in with email code") }
                    } else {
                        OutlinedTextField(value = code, onValueChange = { code = it.filter(Char::isDigit).take(8) }, modifier = Modifier.fillMaxWidth(), label = { Text("Email verification code") }, leadingIcon = { Icon(Icons.Outlined.Pin, contentDescription = null) }, shape = RoundedCornerShape(16.dp), singleLine = true, enabled = !loading, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }))
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    loading = true; error = null
                                    runCatchingActive { authManager.verifyNewDevice(context, email, code) }.onSuccess { onSignedIn() }.onFailure { error = friendlyAuthError(it) }
                                    loading = false
                                }
                            }, modifier = Modifier.fillMaxWidth().height(52.dp), enabled = !loading && code.isNotBlank(), shape = RoundedCornerShape(16.dp)
                        ) { if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Verify email code") }
                        TextButton(onClick = { verifyingEmail = false; code = ""; info = null; error = null }, modifier = Modifier.align(Alignment.CenterHorizontally), enabled = !loading) { Text("Back to login") }
                    }
                }
            }
        }
    }
}

/**
 * Couple photo across the top of the screen, fading into the theme background so the
 * sign-in and lock screens look right in light and dark mode and in every theme.
 */
@Composable
fun BoxScope.AuthPhotoBackdrop(photo: androidx.compose.ui.graphics.painter.Painter) {
    val background = MaterialTheme.colorScheme.background
    Image(
        painter = photo,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.58f).align(Alignment.TopCenter)
    )
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = .18f),
                .16f to Color.Transparent,
                .30f to background.copy(alpha = .10f),
                .50f to background,
                1f to background
            )
        )
    )
}

/** The TwoShare logo on a soft rounded tile. */
@Composable
fun AuthLogo(size: androidx.compose.ui.unit.Dp) {
    Surface(
        shape = RoundedCornerShape(size * .28f),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp
    ) {
        Image(painter = painterResource(R.drawable.twoshare_icon), contentDescription = "TwoShare logo", modifier = Modifier.size(size), contentScale = ContentScale.Crop)
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
