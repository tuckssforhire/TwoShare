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

enum class PasswordSignInResult {
    TRUSTED_DEVICE,
    EMAIL_CODE_REQUIRED
}

class AuthManager {
    private val supabase = SupabaseProvider.client

    private val allowedEmails = setOf(
        "eddierodwards@gmail.com",
        "urban.needs.ph@gmail.com"
    )

    suspend fun restoreTrustedSession(context: Context): Boolean {
        val user = supabase.auth.currentUserOrNull() ?: return false
        val userId = user.id

        if (!isDeviceTrusted(context, userId)) {
            supabase.auth.signOut()
            return false
        }

        return runCatching {
            requireAllowedUser()
            true
        }.getOrElse {
            supabase.auth.signOut()
            false
        }
    }

    suspend fun signInWithPassword(
        context: Context,
        email: String,
        password: String
    ): PasswordSignInResult {
        val normalized = normalizeEmail(email)
        require(password.isNotBlank()) { "Enter your password." }

        supabase.auth.signInWith(Email) {
            this.email = normalized
            this.password = password
        }

        val profile = requireAllowedUser()
        if (isDeviceTrusted(context, profile.id)) {
            return PasswordSignInResult.TRUSTED_DEVICE
        }

        supabase.auth.signOut()
        sendOtp(normalized)
        return PasswordSignInResult.EMAIL_CODE_REQUIRED
    }

    suspend fun startEmailCodeSignIn(email: String) {
        val normalized = normalizeEmail(email)
        if (supabase.auth.currentUserOrNull() != null) {
            supabase.auth.signOut()
        }
        sendOtp(normalized)
    }

    suspend fun verifyNewDevice(
        context: Context,
        email: String,
        code: String
    ) {
        val profile = verifyOtpAndRequireAllowed(email, code)
        markDeviceTrusted(context, profile.id)
    }

    suspend fun startPasswordChangeVerification() {
        val email = currentEmail()
            ?: error("You must be signed in to change your password.")
        sendOtp(normalizeEmail(email))
    }

    suspend fun verifyAndChangePassword(code: String, newPassword: String) {
        val email = currentEmail()
            ?: error("You must be signed in to change your password.")
        require(newPassword.length >= 8) { "New password must be at least 8 characters." }
        val cleanedCode = code.trim()
        require(cleanedCode.isNotEmpty()) { "Enter the verification code from your email." }

        supabase.auth.verifyEmailOtp(
            type = OtpType.Email.EMAIL,
            email = normalizeEmail(email),
            token = cleanedCode
        )
        requireAllowedUser()

        supabase.auth.updateUser {
            password = newPassword
        }
    }

    fun currentEmail(): String? = supabase.auth.currentUserOrNull()?.email

    fun canUseBiometrics(context: Context): Boolean {
        return BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun isBiometricEnabled(context: Context): Boolean {
        val userId = currentUserId() ?: return false
        return prefs(context).getBoolean(biometricKey(userId), false)
    }

    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        val userId = currentUserId() ?: error("Not signed in")
        if (enabled) {
            require(canUseBiometrics(context)) {
                "No fingerprint or face authentication is enrolled on this phone."
            }
        }
        prefs(context)
            .edit()
            .putBoolean(biometricKey(userId), enabled)
            .apply()
    }

    private suspend fun verifyOtpAndRequireAllowed(
        email: String,
        code: String
    ): ProfileRow {
        val normalized = normalizeEmail(email)
        val cleanedCode = code.trim()
        require(cleanedCode.isNotEmpty()) { "Enter the code from your email." }

        supabase.auth.verifyEmailOtp(
            type = OtpType.Email.EMAIL,
            email = normalized,
            token = cleanedCode
        )

        return requireAllowedUser()
    }

    private suspend fun sendOtp(email: String) {
        supabase.auth.signInWith(OTP) {
            this.email = email
            createUser = false
        }
    }

    private fun currentUserId(): String? = supabase.auth.currentUserOrNull()?.id

    private suspend fun requireAllowedUser(): ProfileRow {
        val userId = currentUserId() ?: error("Not signed in")
        val profile = supabase.from("profiles")
            .select {
                filter { eq("id", userId) }
            }
            .decodeList<ProfileRow>()
            .firstOrNull()
        return profile ?: error("This account is not one of the two allowed users.")
    }

    private fun normalizeEmail(email: String): String {
        val normalized = email.trim().lowercase()
        require(normalized in allowedEmails) { "This email is not authorized for TwoShare." }
        return normalized
    }

    private fun isDeviceTrusted(context: Context, userId: String): Boolean {
        val savedFingerprint = prefs(context).getString(trustKey(userId), null)
        return savedFingerprint != null && savedFingerprint == deviceFingerprint(context)
    }

    private fun markDeviceTrusted(context: Context, userId: String) {
        prefs(context)
            .edit()
            .putString(trustKey(userId), deviceFingerprint(context))
            .apply()
    }

    private fun deviceFingerprint(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
        val input = "$androidId:${context.packageName}"
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun trustKey(userId: String) = "trusted_device_$userId"
    private fun biometricKey(userId: String) = "biometric_enabled_$userId"

    companion object {
        private const val PREFS_NAME = "twoshare_auth_v2"
    }
}
