package com.twoshare.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.twoshare.app.call.WebRtcCallManager
import com.twoshare.app.data.TwoShareRepository
import com.twoshare.app.data.loadRemoteIceCandidates
import com.twoshare.app.data.publishIceCandidate
import com.twoshare.app.model.CallRow
import kotlinx.coroutines.CancellationException
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
    val localRenderer = remember(initialCall.id) { SurfaceViewRenderer(context) }
    val remoteRenderer = remember(initialCall.id) { SurfaceViewRenderer(context) }
    val appliedRemoteCandidateIds = remember(initialCall.id) { mutableSetOf<String>() }

    val requiredPermissions = remember(initialCall.id, initialCall.isVideo) {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (initialCall.isVideo) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
    }

    fun permissionsGranted(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var hasPermissions by remember(initialCall.id) { mutableStateOf(permissionsGranted()) }
    var permissionRequested by remember(initialCall.id) { mutableStateOf(false) }
    var manager by remember(initialCall.id) { mutableStateOf<WebRtcCallManager?>(null) }
    var latestCall by remember(initialCall.id) { mutableStateOf(initialCall) }
    var partnerName by remember(initialCall.id) { mutableStateOf("TwoShare") }
    var callState by remember(initialCall.id) { mutableStateOf(if (incoming) "Answering…" else "Ringing…") }
    var error by remember(initialCall.id) { mutableStateOf<String?>(null) }
    var muted by remember(initialCall.id) { mutableStateOf(false) }
    var cameraOn by remember(initialCall.id) { mutableStateOf(initialCall.isVideo) }
    var speakerOn by remember(initialCall.id) { mutableStateOf(initialCall.isVideo) }
    var offerStarted by remember(initialCall.id) { mutableStateOf(false) }
    var lastHandledOfferSdp by remember(initialCall.id) { mutableStateOf<String?>(null) }
    var lastAppliedAnswerSdp by remember(initialCall.id) { mutableStateOf<String?>(null) }
    var initialAnswerPublished by remember(initialCall.id) { mutableStateOf(initialCall.answeredAt != null) }
    var finished by remember(initialCall.id) { mutableStateOf(false) }
    var endingCall by remember(initialCall.id) { mutableStateOf(false) }

    fun friendlyNetworkFailure(throwable: Throwable, fallback: String): String? {
        return if (repository.isTransientNetworkError(throwable)) null else fallback
    }

    fun finishScreen() {
        if (!finished) {
            finished = true
            onFinished()
        }
    }

    fun endAndFinish() {
        if (endingCall || finished) return
        endingCall = true
        scope.launch {
            try {
                if (!incoming && latestCall.status == "ringing") {
                    repository.cancelCall(initialCall.id)
                } else {
                    repository.endCall(initialCall.id)
                }
            } catch (_: CancellationException) {
                // Normal when the TwoLink screen is leaving composition.
            } catch (_: Throwable) {
                // Leaving TwoLink should not be blocked by a backend cleanup failure.
            }
            finishScreen()
        }
    }

    BackHandler(enabled = !finished) {
        endAndFinish()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionRequested = true
        hasPermissions = requiredPermissions.all { permission ->
            results[permission] == true ||
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasPermissions) {
            error = if (initialCall.isVideo) {
                "Microphone, camera and Nearby devices permission are required for TwoLink."
            } else {
                "Microphone and Nearby devices permission are required for TwoLink."
            }
        }
    }

    LaunchedEffect(initialCall.id) {
        partnerName = try {
            repository.partnerName(initialCall)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            "TwoShare"
        }
        if (!hasPermissions && !permissionRequested) {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    DisposableEffect(initialCall.id, hasPermissions) {
        if (!hasPermissions) {
            onDispose { }
        } else {
            val rtcResult = runCatching {
                WebRtcCallManager(
                    context = context,
                    videoEnabledForCall = initialCall.isVideo,
                    initiator = !incoming,
                    localRenderer = localRenderer,
                    remoteRenderer = remoteRenderer,
                    onLocalSdpReady = { sdp ->
                        scope.launch {
                            try {
                                if (incoming) {
                                    if (!initialAnswerPublished) {
                                        repository.acceptCall(initialCall.id, sdp)
                                        initialAnswerPublished = true
                                    } else {
                                        repository.setCallAnswer(initialCall.id, sdp)
                                    }
                                    callState = "Connecting…"
                                } else {
                                    repository.setCallOffer(initialCall.id, sdp)
                                    callState = if (latestCall.answeredAt == null) {
                                        "Ringing…"
                                    } else {
                                        "Reconnecting…"
                                    }
                                }
                                error = null
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (t: Throwable) {
                                if (!finished) {
                                    if (repository.isTransientNetworkError(t)) {
                                        callState = "Reconnecting…"
                                        error = null
                                    } else {
                                        error = "Unable to update TwoLink connection."
                                    }
                                }
                            }
                        }
                    },
                    onLocalIceCandidate = { candidate ->
                        scope.launch {
                            try {
                                repository.publishIceCandidate(
                                    callId = initialCall.id,
                                    sdpMid = candidate.sdpMid,
                                    sdpMLineIndex = candidate.sdpMLineIndex,
                                    candidate = candidate.sdp
                                )
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (t: Throwable) {
                                if (!finished && callState != "Connected") {
                                    if (repository.isTransientNetworkError(t)) {
                                        callState = "Reconnecting…"
                                        error = null
                                    } else {
                                        error = "Unable to update TwoLink network path."
                                    }
                                }
                            }
                        }
                    },
                    onConnectionState = { state ->
                        callState = state
                        if (state == "Connected" || state.startsWith("Reconnecting")) {
                            error = null
                        }
                    },
                    onError = { message ->
                        if (!finished) error = message
                    }
                )
            }

            val rtc = rtcResult.getOrNull()
            if (rtc == null) {
                error = "Unable to initialize TwoLink on this phone: ${rtcResult.exceptionOrNull()?.localizedMessage ?: "WebRTC startup failed"}"
                callState = "Unavailable"
                onDispose { }
            } else {
                manager = rtc
                rtc.setSpeakerEnabled(speakerOn)
                onDispose {
                    manager = null
                    rtc.dispose()
                }
            }
        }
    }

    LaunchedEffect(initialCall.id, manager, incoming) {
        val rtc = manager ?: return@LaunchedEffect
        if (!incoming && !offerStarted) {
            offerStarted = true
            rtc.startCaller()
        }
    }

    // Poll the shared call row for both initial signaling and later ICE-restart renegotiation.
    // A changed offer/answer SDP is handled once, allowing the same TwoLink session to recover
    // when either phone moves between Wi-Fi and mobile data.
    LaunchedEffect(initialCall.id, manager, incoming, hasPermissions) {
        val rtc = manager ?: return@LaunchedEffect
        if (!hasPermissions) return@LaunchedEffect

        while (isActive && !finished) {
            val fresh = try {
                repository.getCall(initialCall.id)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                if (repository.isTransientNetworkError(t)) {
                    callState = "Reconnecting…"
                    error = null
                }
                null
            }

            if (fresh != null) {
                latestCall = fresh

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

                if (incoming) {
                    val offer = fresh.offerSdp
                    if (!offer.isNullOrBlank() && offer != lastHandledOfferSdp) {
                        lastHandledOfferSdp = offer
                        callState = if (initialAnswerPublished) "Reconnecting…" else "Answering…"
                        rtc.startCallee(offer)
                    }
                } else {
                    val answer = fresh.answerSdp
                    if (!answer.isNullOrBlank() && answer != lastAppliedAnswerSdp) {
                        lastAppliedAnswerSdp = answer
                        rtc.applyAnswer(answer)
                    }
                }
            }
            delay(450)
        }
    }

    LaunchedEffect(initialCall.id, manager) {
        val rtc = manager ?: return@LaunchedEffect
        while (isActive && !finished) {
            try {
                val candidates = repository.loadRemoteIceCandidates(initialCall.id)
                candidates.forEach { candidate ->
                    if (appliedRemoteCandidateIds.add(candidate.id)) {
                        rtc.addRemoteIceCandidate(
                            sdpMid = candidate.sdpMid,
                            sdpMLineIndex = candidate.sdpMLineIndex,
                            candidate = candidate.candidate
                        )
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                if (!finished && callState != "Connected") {
                    if (repository.isTransientNetworkError(t)) {
                        callState = "Reconnecting…"
                        error = null
                    } else {
                        friendlyNetworkFailure(t, "Unable to update TwoLink network path.")?.let {
                            error = it
                        }
                    }
                }
            }
            delay(400)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF11111B))
    ) {
        if (initialCall.isVideo && hasPermissions && manager != null) {
            AndroidView(
                factory = { remoteRenderer },
                modifier = Modifier.fillMaxSize()
            )

            AndroidView(
                factory = { localRenderer },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 88.dp, end = 16.dp)
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

        FilledTonalButton(
            onClick = { endAndFinish() },
            enabled = !endingCall,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = 24.dp)
        ) {
            Text("← Back")
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "TwoLink",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                "${if (initialCall.isVideo) "Video" else "Voice"} • $callState",
                color = Color.White.copy(alpha = 0.8f)
            )
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 70.dp)
                )
            }
        }

        if (!hasPermissions) {
            Button(
                onClick = { permissionLauncher.launch(requiredPermissions.toTypedArray()) },
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text("Grant TwoLink permissions")
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            color = Color(0x8811111B)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                CompactCallControl(
                    icon = if (muted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = if (muted) "Unmute microphone" else "Mute microphone",
                    enabled = hasPermissions && manager != null,
                    onClick = {
                        muted = !muted
                        manager?.setMuted(muted)
                    }
                )

                CompactCallControl(
                    icon = if (speakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                    contentDescription = if (speakerOn) "Use earpiece" else "Use speaker",
                    enabled = hasPermissions && manager != null,
                    onClick = {
                        speakerOn = !speakerOn
                        manager?.setSpeakerEnabled(speakerOn)
                    }
                )

                if (initialCall.isVideo) {
                    CompactCallControl(
                        icon = if (cameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                        contentDescription = if (cameraOn) "Turn camera off" else "Turn camera on",
                        enabled = hasPermissions && manager != null,
                        onClick = {
                            cameraOn = !cameraOn
                            manager?.setVideoEnabled(cameraOn)
                        }
                    )

                    CompactCallControl(
                        icon = Icons.Default.Cameraswitch,
                        contentDescription = "Flip camera",
                        enabled = hasPermissions && manager != null && cameraOn,
                        onClick = { manager?.switchCamera() }
                    )
                }

                CompactCallControl(
                    icon = Icons.Default.CallEnd,
                    contentDescription = "End TwoLink",
                    enabled = !endingCall,
                    destructive = true,
                    larger = true,
                    onClick = { endAndFinish() }
                )
            }
        }
    }
}

@Composable
private fun CompactCallControl(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false,
    larger: Boolean = false
) {
    val size = if (larger) 60.dp else 52.dp
    val backgroundColor = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        Color(0xCC2D2D38)
    }

    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = backgroundColor,
        tonalElevation = if (destructive) 0.dp else 2.dp
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
                modifier = Modifier.size(if (larger) 30.dp else 26.dp)
            )
        }
    }
}
