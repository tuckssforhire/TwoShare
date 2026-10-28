package com.twoshare.app.data

import android.content.Context
import android.provider.Settings
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.OTP
import java.security.MessageDigest

enum class PasswordSignInResult {
    SIGNED_IN,
    EMAIL_CODE_REQUIRED
}

class PasswordAuthManager(
    private val repository: TwoShareRepository
) {
    private val supabase = SupabaseProvider.client

    private val allowedEmails = setOf(
        "eddierodwards@gmail.com",
        "misamitchlailarene@gmail.com"
    )

    suspend fun restoreTrustedSession(context: Context): Boolean {
        val user = supabase.auth.currentUserOrNull() ?: return false
        val email = user.email?.trim()?.lowercase() ?: run {
            supabase.auth.signOut()
            return false
        }

        if (!isTrustedDevice(context, email)) {
            supabase.auth.signOut()
            return false
        }

        return runCatching {
            repository.requireAllowedUser()
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
        repository.requireAllowedUser()

        if (isTrustedDevice(context, normalized)) {
            return PasswordSignInResult.SIGNED_IN
        }

        // The password has already been verified. End the first-factor session
        // before sending the email code so app content is not accessible while
        // the new device is awaiting its second verification step.
        supabase.auth.signOut()
        sendOtp(normalized)
        return PasswordSignInResult.EMAIL_CODE_REQUIRED
    }

    suspend fun sendPasswordSetupCode(email: String) {
        val normalized = normalizeEmail(email)
        if (supabase.auth.currentUserOrNull() != null) {
            supabase.auth.signOut()
        }
        sendOtp(normalized)
    }

    suspend fun verifyEmailCode(
        context: Context,
        email: String,
        code: String,
        password: String,
        setPassword: Boolean
    ) {
        val normalized = normalizeEmail(email)
        val cleanedCode = code.trim()
        require(cleanedCode.isNotEmpty()) { "Enter the code from your email." }

        supabase.auth.verifyEmailOtp(
            type = OtpType.Email.EMAIL,
            email = normalized,
            token = cleanedCode
        )
        repository.requireAllowedUser()

        if (setPassword) {
            require(password.length >= 6) { "Password must be at least 6 characters." }
            supabase.auth.updateUser {
                this.password = password
            }
        }

        markTrustedDevice(context, normalized)
    }

    private suspend fun sendOtp(email: String) {
        supabase.auth.signInWith(OTP) {
            this.email = email
            createUser = false
        }
    }

    private fun normalizeEmail(email: String): String {
        val normalized = email.trim().lowercase()
        require(normalized in allowedEmails) { "This email is not authorized for TwoShare." }
        return normalized
    }

    private fun isTrustedDevice(context: Context, email: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedFingerprint = prefs.getString(trustKey(email), null)
        return savedFingerprint != null && savedFingerprint == deviceFingerprint(context)
    }

    private fun markTrustedDevice(context: Context, email: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(trustKey(email), deviceFingerprint(context))
            .apply()
    }

    private fun trustKey(email: String): String = "trusted_${sha256(email)}"

    private fun deviceFingerprint(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
        return sha256("${context.packageName}|$androidId")
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val PREFS_NAME = "twoshare_trusted_devices"
    }
}
