package com.twoshare.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.twoshare.app.call.WebRtcCallManager
import com.twoshare.app.data.TwoShareRepository
import com.twoshare.app.model.CallRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer

@Composable
fun CallScreen(
    repository: TwoShareRepository,
    initialCall: CallRow,
    incoming: Boolean,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val localRenderer = remember { SurfaceViewRenderer(context) }
    val remoteRenderer = remember { SurfaceViewRenderer(context) }

    val requiredPermissions = remember(initialCall.isVideo) {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (initialCall.isVideo) add(Manifest.permission.CAMERA)
        }
    }

    fun permissionsGranted(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var hasPermissions by remember { mutableStateOf(permissionsGranted()) }
    var permissionRequested by remember { mutableStateOf(false) }
    var manager by remember { mutableStateOf<WebRtcCallManager?>(null) }
    var latestCall by remember { mutableStateOf(initialCall) }
    var partnerName by remember { mutableStateOf("TwoShare") }
    var callState by remember { mutableStateOf(if (incoming) "Answering…" else "Ringing…") }
    var error by remember { mutableStateOf<String?>(null) }
    var muted by remember { mutableStateOf(false) }
    var cameraOn by remember { mutableStateOf(initialCall.isVideo) }
    var speakerOn by remember { mutableStateOf(initialCall.isVideo) }
    var offerStarted by remember { mutableStateOf(false) }
    var answerStarted by remember { mutableStateOf(false) }
    var answerApplied by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    fun finishScreen() {
        if (!finished) {
            finished = true
            onFinished()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionRequested = true
        hasPermissions = requiredPermissions.all { results[it] == true ||
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasPermissions) {
            error = "Microphone${if (initialCall.isVideo) " and camera" else ""} permission is required for this call."
        }
    }

    LaunchedEffect(Unit) {
        partnerName = runCatching { repository.partnerName(initialCall) }.getOrDefault("TwoShare")
        if (!hasPermissions && !permissionRequested) {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    DisposableEffect(hasPermissions) {
        if (!hasPermissions) {
            onDispose { }
        } else {
            val rtc = WebRtcCallManager(
                context = context,
                videoEnabledForCall = initialCall.isVideo,
                localRenderer = localRenderer,
                remoteRenderer = remoteRenderer,
                onLocalSdpReady = { sdp ->
                    scope.launch {
                        runCatching {
                            if (incoming) {
                                repository.acceptCall(initialCall.id, sdp)
                                callState = "Connecting…"
                            } else {
                                repository.setCallOffer(initialCall.id, sdp)
                            }
                        }.onFailure {
                            error = it.localizedMessage ?: "Unable to send call setup."
                        }
                    }
                },
                onConnectionState = { state -> callState = state },
                onError = { message -> error = message }
            )
            manager = rtc
            rtc.setSpeakerEnabled(speakerOn)
            onDispose {
                manager = null
                rtc.dispose()
            }
        }
    }

    LaunchedEffect(manager, incoming) {
        val rtc = manager ?: return@LaunchedEffect
        if (!incoming && !offerStarted) {
            offerStarted = true
            rtc.startCaller()
        }
    }

    LaunchedEffect(manager, incoming, hasPermissions) {
        val rtc = manager ?: return@LaunchedEffect
        if (!incoming || !hasPermissions || answerStarted) return@LaunchedEffect

        while (isActive && !answerStarted) {
            val fresh = runCatching { repository.getCall(initialCall.id) }.getOrNull()
            if (fresh == null) {
                delay(700)
                continue
            }
            latestCall = fresh
            when (fresh.status) {
                "cancelled", "ended" -> {
                    finishScreen()
                    return@LaunchedEffect
                }
            }
            val offer = fresh.offerSdp
            if (!offer.isNullOrBlank()) {
                answerStarted = true
                rtc.startCallee(offer)
                break
            }
            delay(500)
        }
    }

    LaunchedEffect(manager, incoming) {
        val rtc = manager ?: return@LaunchedEffect
        while (isActive && !finished) {
            val fresh = runCatching { repository.getCall(initialCall.id) }.getOrNull()
            if (fresh != null) {
                latestCall = fresh
                if (!incoming && !answerApplied && !fresh.answerSdp.isNullOrBlank()) {
                    answerApplied = true
                    rtc.applyAnswer(fresh.answerSdp)
                    callState = "Connecting…"
                }
                when (fresh.status) {
                    "rejected" -> {
                        callState = "Declined"
                        delay(500)
                        finishScreen()
                        return@LaunchedEffect
                    }
                    "cancelled", "ended" -> {
                        finishScreen()
                        return@LaunchedEffect
                    }
                }
            }
            delay(700)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF11111B))
    ) {
        if (initialCall.isVideo && hasPermissions) {
            AndroidView(
                factory = { remoteRenderer },
                modifier = Modifier.fillMaxSize()
            )

            AndroidView(
                factory = { localRenderer },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 24.dp, end = 16.dp)
                    .width(120.dp)
                    .height(170.dp)
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    modifier = Modifier.size(120.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            partnerName.take(1).uppercase(),
                            style = MaterialTheme.typography.displayLarge
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(partnerName, color = Color.White, style = MaterialTheme.typography.headlineSmall)
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (initialCall.isVideo) "Video call" else "Voice call",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge
            )
            Text(callState, color = Color.White.copy(alpha = 0.8f))
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }

        if (!hasPermissions) {
            Button(
                onClick = { permissionLauncher.launch(requiredPermissions.toTypedArray()) },
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text("Grant call permissions")
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 34.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = {
                    muted = !muted
                    manager?.setMuted(muted)
                },
                enabled = hasPermissions
            ) {
                Text(if (muted) "Unmute" else "Mute")
            }

            FilledTonalButton(
                onClick = {
                    speakerOn = !speakerOn
                    manager?.setSpeakerEnabled(speakerOn)
                },
                enabled = hasPermissions
            ) {
                Text(if (speakerOn) "Speaker ✓" else "Speaker")
            }

            if (initialCall.isVideo) {
                FilledTonalButton(
                    onClick = {
                        cameraOn = !cameraOn
                        manager?.setVideoEnabled(cameraOn)
                    },
                    enabled = hasPermissions
                ) {
                    Text(if (cameraOn) "Camera ✓" else "Camera")
                }

                FilledTonalButton(
                    onClick = { manager?.switchCamera() },
                    enabled = hasPermissions && cameraOn
                ) {
                    Text("Flip")
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        runCatching {
                            if (!incoming && latestCall.status == "ringing") {
                                repository.cancelCall(initialCall.id)
                            } else {
                                repository.endCall(initialCall.id)
                            }
                        }
                        finishScreen()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("End")
            }
        }
    }
}
