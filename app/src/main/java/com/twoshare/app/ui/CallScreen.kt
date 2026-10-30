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
    val localRenderer = remember { SurfaceViewRenderer(context) }
    val remoteRenderer = remember { SurfaceViewRenderer(context) }
    val appliedRemoteCandidateIds = remember { mutableSetOf<String>() }

    val requiredPermissions = remember(initialCall.isVideo) {
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
    var endingCall by remember { mutableStateOf(false) }

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
                // Normal when the call screen is leaving composition.
            } catch (_: Throwable) {
                // Leaving the call screen should not be blocked by a backend cleanup failure.
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
                "Microphone, camera and Nearby devices permission are required for calling."
            } else {
                "Microphone and Nearby devices permission are required for calling."
            }
        }
    }

    LaunchedEffect(Unit) {
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

    DisposableEffect(hasPermissions) {
        if (!hasPermissions) {
            onDispose { }
        } else {
            val rtcResult = runCatching {
                WebRtcCallManager(
                    context = context,
                    videoEnabledForCall = initialCall.isVideo,
                    localRenderer = localRenderer,
                    remoteRenderer = remoteRenderer,
                    onLocalSdpReady = { sdp ->
                        scope.launch {
                            try {
                                if (incoming) {
                                    repository.acceptCall(initialCall.id, sdp)
                                    callState = "Connecting…"
                                } else {
                                    repository.setCallOffer(initialCall.id, sdp)
                                    callState = "Ringing…"
                                }
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (t: Throwable) {
                                if (!finished) {
                                    error = "Unable to send call setup: ${t.localizedMessage ?: "unknown error"}"
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
                                    error = "Unable to send network candidate: ${t.localizedMessage ?: "unknown error"}"
                                }
                            }
                        }
                    },
                    onConnectionState = { state ->
                        callState = state
                        if (state == "Connected") {
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
                error = "Unable to initialize calling on this phone: ${rtcResult.exceptionOrNull()?.localizedMessage ?: "WebRTC startup failed"}"
                callState = "Call unavailable"
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
            val fresh = try {
                repository.getCall(initialCall.id)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                null
            }
            if (fresh == null) {
                delay(500)
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
            delay(400)
        }
    }

    LaunchedEffect(manager) {
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
                    error = "Unable to receive network candidate: ${t.localizedMessage ?: "unknown error"}"
                }
            }
            delay(400)
        }
    }

    LaunchedEffect(manager, incoming) {
        val rtc = manager ?: return@LaunchedEffect
        while (isActive && !finished) {
            val fresh = try {
                repository.getCall(initialCall.id)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                null
            }
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
            delay(500)
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
                if (initialCall.isVideo) "Video call" else "Voice call",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge
            )
            Text(callState, color = Color.White.copy(alpha = 0.8f))
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
                Text("Grant call permissions")
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            color = Color(0xCC11111B)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            muted = !muted
                            manager?.setMuted(muted)
                        },
                        enabled = hasPermissions && manager != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (muted) "Unmute" else "Mute")
                    }

                    FilledTonalButton(
                        onClick = {
                            speakerOn = !speakerOn
                            manager?.setSpeakerEnabled(speakerOn)
                        },
                        enabled = hasPermissions && manager != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (speakerOn) "Speaker ✓" else "Speaker")
                    }
                }

                if (initialCall.isVideo) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                cameraOn = !cameraOn
                                manager?.setVideoEnabled(cameraOn)
                            },
                            enabled = hasPermissions && manager != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (cameraOn) "Camera ✓" else "Camera")
                        }

                        FilledTonalButton(
                            onClick = { manager?.switchCamera() },
                            enabled = hasPermissions && manager != null && cameraOn,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Flip camera")
                        }
                    }
                }

                Button(
                    onClick = { endAndFinish() },
                    enabled = !endingCall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(if (endingCall) "Ending…" else "End Call")
                }
            }
        }
    }
}
