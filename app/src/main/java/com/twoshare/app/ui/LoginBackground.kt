package com.twoshare.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.media.ExifInterface
import com.twoshare.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.twoshare.app.data.SupabaseProvider
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.storage.storage
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Background photo for the sign-in and lock screens, shared by both TwoShare accounts.
 *
 * The choice lives in Supabase (shared_settings.login_background; a custom photo in the private
 * bucket). Because those screens appear before sign-in, each phone keeps a local copy and
 * refreshes it whenever it is signed in; a custom photo is downloaded only when it changes.
 * Values: "random", "builtin:<index>" or "custom:<storage path>".
 */
object LoginBackgroundStore {
    const val RANDOM = "random"
    const val CUSTOM = "custom"
    private const val SETTING_KEY = "login_background"
    private const val PREFS = "twoshare_login_background"
    private const val KEY = "choice"
    private const val KEY_SYNCED = "synced_value"
    private const val FILE = "login_background.jpg"
    private const val MAX_EDGE = 1600

    val builtIns = listOf(
        R.drawable.login_photo_01, R.drawable.login_photo_02, R.drawable.login_photo_03, R.drawable.login_photo_04,
        R.drawable.login_photo_05, R.drawable.login_photo_06, R.drawable.login_photo_07, R.drawable.login_photo_08,
        R.drawable.login_photo_09, R.drawable.login_photo_10
    )

    @Serializable private data class SettingRow(val key: String, val value: String)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Local copy used by the screens: "random", "custom" or "builtin:<index>". */
    fun choice(context: Context): String = prefs(context).getString(KEY, RANDOM) ?: RANDOM

    private fun applyLocal(context: Context, local: String, synced: String) {
        prefs(context).edit().putString(KEY, local).putString(KEY_SYNCED, synced).apply()
        if (local != CUSTOM) customFile(context).delete()
    }

    fun customFile(context: Context) = File(context.filesDir, FILE)

    fun loadCustom(context: Context): Bitmap? = customFile(context).takeIf { it.exists() }
        ?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }

    private fun writeCustomFile(context: Context, bytes: ByteArray) {
        val tmp = File(context.filesDir, "$FILE.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(customFile(context))) { tmp.copyTo(customFile(context), overwrite = true); tmp.delete() }
    }

    /** Built-in or random background: saved for both of you. */
    suspend fun share(context: Context, value: String) {
        require(value == RANDOM || value.startsWith("builtin:"))
        upsertSetting(value)
        applyLocal(context, value, value)
    }

    /** Your own photo: resized on the phone, uploaded once, then shown on both phones. */
    suspend fun shareCustom(context: Context, uri: Uri) {
        val uid = SupabaseProvider.client.auth.currentUserOrNull()?.id ?: error("Your session has expired. Please sign in again.")
        val bytes = prepareCustom(context, uri)
        val path = "$uid/login-background/${UUID.randomUUID()}.jpg"
        val previous = prefs(context).getString(KEY_SYNCED, null)
        SupabaseProvider.client.storage.from("twoshare").upload(path, bytes) { upsert = false }
        val value = "custom:$path"
        upsertSetting(value)
        withContext(Dispatchers.IO) { writeCustomFile(context, bytes) }
        applyLocal(context, CUSTOM, value)
        // Tidy up your previous custom photo; ignored if storage rules don't allow deletes.
        previous?.removePrefix("custom:")?.takeIf { previous.startsWith("custom:$uid/") }
            ?.let { old -> runCatching { SupabaseProvider.client.storage.from("twoshare").delete(old) } }
    }

    /**
     * Brings this phone's copy up to date with the shared setting. Cheap when nothing changed
     * (one tiny row); a custom photo is downloaded only when your partner picked a new one.
     */
    suspend fun sync(context: Context) {
        SupabaseProvider.client.auth.currentUserOrNull() ?: return
        val row = SupabaseProvider.client.from("shared_settings")
            .select { filter { eq("key", SETTING_KEY) } }
            .decodeList<SettingRow>().firstOrNull() ?: return
        val value = row.value
        val localOk = prefs(context).getString(KEY_SYNCED, null) == value &&
            (!value.startsWith("custom:") || customFile(context).exists())
        if (localOk) return
        when {
            value == RANDOM || value.startsWith("builtin:") -> applyLocal(context, value, value)
            value.startsWith("custom:") -> {
                val bytes = SupabaseProvider.client.storage.from("twoshare").downloadAuthenticated(value.removePrefix("custom:"))
                withContext(Dispatchers.IO) { writeCustomFile(context, bytes) }
                applyLocal(context, CUSTOM, value)
            }
        }
    }

    private suspend fun upsertSetting(value: String) {
        SupabaseProvider.client.from("shared_settings").upsert(SettingRow(SETTING_KEY, value)) { onConflict = "key" }
    }

    /** Upright JPEG, longest edge at most 1600 px. */
    private suspend fun prepareCustom(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: error("Unable to read that photo.")
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "That file isn't a supported photo." }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        var bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: error("Unable to read that photo.")
        val rotation = runCatching {
            resolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height))
        if (rotation != 0f || scale < 1f) {
            val matrix = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
    }
}

/** The configured background, resolved once per screen visit ("random" picks a new one each time). */
@Composable
fun rememberLoginBackground(): Painter {
    val context = LocalContext.current
    val choice = remember { LoginBackgroundStore.choice(context) }
    val custom = remember(choice) { if (choice == LoginBackgroundStore.CUSTOM) LoginBackgroundStore.loadCustom(context) else null }
    val builtIn = remember(choice) {
        choice.removePrefix("builtin:").toIntOrNull()?.takeIf { choice.startsWith("builtin:") }
            ?.let { LoginBackgroundStore.builtIns.getOrNull(it) }
            ?: LoginBackgroundStore.builtIns.random()
    }
    return if (custom != null) remember(custom) { BitmapPainter(custom.asImageBitmap()) } else painterResource(builtIn)
}

@Composable
fun LoginBackgroundDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var choice by remember { mutableStateOf(LoginBackgroundStore.choice(context)) }
    var customPreview by remember { mutableStateOf(LoginBackgroundStore.loadCustom(context)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        // Pick up a change your partner made since this phone last synced.
        runCatchingActive { LoginBackgroundStore.sync(context) }.onSuccess {
            choice = LoginBackgroundStore.choice(context); customPreview = LoginBackgroundStore.loadCustom(context)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = true; error = null
            runCatchingActive { LoginBackgroundStore.shareCustom(context, uri) }
                .onSuccess { choice = LoginBackgroundStore.CUSTOM; customPreview = LoginBackgroundStore.loadCustom(context) }
                .onFailure { error = friendlyBackgroundError(it) }
            busy = false
        }
    }
    fun select(value: String) {
        if (busy) return
        val before = choice
        choice = value
        scope.launch {
            busy = true; error = null
            runCatchingActive { LoginBackgroundStore.share(context, value) }
                .onSuccess { customPreview = null }
                .onFailure { choice = before; error = friendlyBackgroundError(it) }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Login background") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Shown on the sign-in and lock screens on both of your phones.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.heightIn(max = 380.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        BackgroundTile(selected = choice == LoginBackgroundStore.RANDOM, onClick = { select(LoginBackgroundStore.RANDOM) }) {
                            TileLabel(Icons.Default.Shuffle, "Random")
                        }
                    }
                    item {
                        BackgroundTile(selected = choice == LoginBackgroundStore.CUSTOM, onClick = { if (!busy) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                            val preview = customPreview
                            if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            else if (preview != null) Image(remember(preview) { BitmapPainter(preview.asImageBitmap()) }, "Your photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else TileLabel(Icons.Default.AddPhotoAlternate, "Your photo")
                        }
                    }
                    items(LoginBackgroundStore.builtIns.indices.toList()) { index ->
                        BackgroundTile(selected = choice == "builtin:$index", onClick = { select("builtin:$index") }) {
                            Image(painterResource(LoginBackgroundStore.builtIns[index]), "Background ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    }
                }
                if (choice == LoginBackgroundStore.CUSTOM && customPreview != null)
                    TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !busy) { Text("Choose a different photo") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun BackgroundTile(selected: Boolean, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.aspectRatio(0.7f)
    ) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            content()
            if (selected) Surface(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Default.Check, "Selected", Modifier.padding(3.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun TileLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    }
}

private fun friendlyBackgroundError(t: Throwable): String {
    val raw = t.localizedMessage.orEmpty()
    return when {
        "shared_settings" in raw -> "Shared backgrounds aren't set up in Supabase yet. Run supabase/shared_lists_and_realtime.sql once."
        raw.isBlank() || raw.length > 200 || "url=https://" in raw.lowercase() -> "Couldn't save the background. Check your connection and try again."
        else -> raw.lineSequence().firstOrNull()?.take(180) ?: "Couldn't save the background."
    }
}
