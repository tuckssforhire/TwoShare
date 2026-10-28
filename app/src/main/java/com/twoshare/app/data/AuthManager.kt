package com.twoshare.app.data

import android.content.Context
import android.provider.Settings
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
        "misamitchlailarene@gmail.com"
    )

    fun trustExistingSessionAfterUpgrade(context: Context) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_TRUST_MIGRATION_DONE, false)) return

        currentUserId()?.let { userId ->
            markDeviceTrusted(context, userId)
        }
        prefs.edit().putBoolean(KEY_TRUST_MIGRATION_DONE, true).apply()
    }

    fun isAppAuthorized(context: Context): Boolean {
        val userId = currentUserId() ?: return false
        return isDeviceTrusted(context, userId)
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

        supabase.auth.signInWith(OTP) {
            this.email = normalized
            createUser = false
        }

        return PasswordSignInResult.EMAIL_CODE_REQUIRED
    }

    suspend fun verifyNewDevice(
        context: Context,
        email: String,
        code: String
    ) {
        val normalized = normalizeEmail(email)
        val cleanedCode = code.trim()
        require(cleanedCode.isNotEmpty()) { "Enter the code from your email." }

        supabase.auth.verifyEmailOtp(
            type = OtpType.Email.EMAIL,
            email = normalized,
            token = cleanedCode
        )

        val profile = requireAllowedUser()
        markDeviceTrusted(context, profile.id)
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

    companion object {
        private const val PREFS_NAME = "twoshare_auth"
        private const val KEY_TRUST_MIGRATION_DONE = "trusted_device_migration_v1"
    }
}
