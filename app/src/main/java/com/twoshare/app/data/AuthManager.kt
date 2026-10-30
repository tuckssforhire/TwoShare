package com.twoshare.app.data

import android.content.Context
import android.provider.Settings
import androidx.biometric.BiometricManager
import com.twoshare.app.model.ProfileRow
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.postgrest.from
import java.security.MessageDigest

class AuthManager {
    private val supabase = SupabaseProvider.client
    private val allowedEmails = setOf("eddierodwards@gmail.com", "misamitchlailarene@gmail.com")

    suspend fun restoreTrustedSession(context: Context): Boolean {
        val user = supabase.auth.currentUserOrNull() ?: return false
        if (!isDeviceTrusted(context, user.id)) { supabase.auth.signOut(); return false }
        return runCatching {
            requireAllowedUser()
            rememberEmail(context, user.email.orEmpty())
            true
        }.getOrElse { supabase.auth.signOut(); false }
    }

    suspend fun signInWithPassword(context: Context, email: String, password: String): PasswordSignInResult {
        val normalized = normalizeEmail(email)
        require(password.isNotBlank()) { "Enter your password." }
        supabase.auth.signInWith(Email) { this.email = normalized; this.password = password }
        val profile = requireAllowedUser()
        markDeviceTrusted(context, profile.id)
        rememberEmail(context, normalized)
        return PasswordSignInResult.TRUSTED_DEVICE
    }

    suspend fun startEmailCodeSignIn(email: String) {
        val normalized = normalizeEmail(email)
        if (supabase.auth.currentUserOrNull() != null) supabase.auth.signOut()
        sendOtp(normalized)
    }

    suspend fun verifyNewDevice(context: Context, email: String, code: String) {
        val profile = verifyOtpAndRequireAllowed(email, code)
        markDeviceTrusted(context, profile.id)
        rememberEmail(context, normalizeEmail(email))
    }

    suspend fun startPasswordChangeVerification() {
        require(currentUserId() != null) { "You must be signed in to change your password." }
        supabase.auth.reauthenticate()
    }

    suspend fun verifyAndChangePassword(code: String, newPassword: String) {
        require(currentUserId() != null) { "You must be signed in to change your password." }
        require(newPassword.length >= 8) { "New password must be at least 8 characters." }
        val cleanedCode = code.trim()
        require(cleanedCode.isNotEmpty()) { "Enter the verification code from your email." }
        supabase.auth.updateUser { password = newPassword; nonce = cleanedCode }
        requireAllowedUser()
    }

    suspend fun currentProfile(): ProfileRow = requireAllowedUser()

    suspend fun partnerProfile(): ProfileRow? {
        val userId = currentUserId() ?: return null
        return supabase.from("profiles").select().decodeList<ProfileRow>().firstOrNull { it.id != userId }
    }

    suspend fun touchLastActive() {
        val userId = currentUserId() ?: return
        supabase.from("profiles").update({
            set("last_active_at", java.time.OffsetDateTime.now().toString())
        }) { filter { eq("id", userId) } }
    }

    suspend fun updateDisplayName(displayName: String): ProfileRow {
        val userId = currentUserId() ?: error("You must be signed in to change your display name.")
        val cleaned = displayName.trim()
        require(cleaned.isNotEmpty()) { "Display name cannot be blank." }
        require(cleaned.length <= 60) { "Display name must be 60 characters or fewer." }
        requireAllowedUser()
        val updated = supabase.from("profiles").update({ set("display_name", cleaned) }) {
            select(); filter { eq("id", userId) }
        }.decodeList<ProfileRow>()
        return updated.singleOrNull() ?: error("Unable to update display name.")
    }

    fun currentEmail(): String? = supabase.auth.currentUserOrNull()?.email
    fun rememberedEmail(context: Context): String = prefs(context).getString(KEY_REMEMBERED_EMAIL, "").orEmpty()
    fun rememberEmail(context: Context, email: String) {
        val normalized = email.trim().lowercase()
        if (normalized.isNotBlank()) prefs(context).edit().putString(KEY_REMEMBERED_EMAIL, normalized).apply()
    }

    fun canUseBiometrics(context: Context): Boolean = BiometricManager.from(context).canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_WEAK
    ) == BiometricManager.BIOMETRIC_SUCCESS

    fun isBiometricEnabled(context: Context): Boolean {
        val userId = currentUserId() ?: return false
        return prefs(context).getBoolean(biometricKey(userId), false)
    }

    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        val userId = currentUserId() ?: error("Not signed in")
        if (enabled) require(canUseBiometrics(context)) { "No fingerprint or face authentication is enrolled on this phone." }
        prefs(context).edit().putBoolean(biometricKey(userId), enabled).apply()
    }

    private suspend fun verifyOtpAndRequireAllowed(email: String, code: String): ProfileRow {
        val normalized = normalizeEmail(email)
        val cleanedCode = code.trim()
        require(cleanedCode.isNotEmpty()) { "Enter the code from your email." }
        supabase.auth.verifyEmailOtp(type = OtpType.Email.EMAIL, email = normalized, token = cleanedCode)
        return requireAllowedUser()
    }

    private suspend fun sendOtp(email: String) {
        supabase.auth.signInWith(OTP) { this.email = email; createUser = false }
    }

    private fun currentUserId(): String? = supabase.auth.currentUserOrNull()?.id

    private suspend fun requireAllowedUser(): ProfileRow {
        val userId = currentUserId() ?: error("Not signed in")
        return supabase.from("profiles").select { filter { eq("id", userId) } }.decodeList<ProfileRow>().firstOrNull()
            ?: error("This account is not one of the two allowed users.")
    }

    private fun normalizeEmail(email: String): String {
        val normalized = email.trim().lowercase()
        require(normalized in allowedEmails) { "This email is not authorized for TwoShare." }
        return normalized
    }

    private fun isDeviceTrusted(context: Context, userId: String): Boolean =
        prefs(context).getString(trustKey(userId), null) == deviceFingerprint(context)

    private fun markDeviceTrusted(context: Context, userId: String) {
        prefs(context).edit().putString(trustKey(userId), deviceFingerprint(context)).apply()
    }

    private fun deviceFingerprint(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        return MessageDigest.getInstance("SHA-256").digest("$androidId:${context.packageName}".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun trustKey(userId: String) = "trusted_device_$userId"
    private fun biometricKey(userId: String) = "biometric_enabled_$userId"

    companion object {
        private const val PREFS_NAME = "twoshare_auth_v2"
        private const val KEY_REMEMBERED_EMAIL = "remembered_email"
    }
}

enum class PasswordSignInResult { TRUSTED_DEVICE, EMAIL_CODE_REQUIRED }
