package com.twoshare.app.call

import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.webrtc.*

class WebRtcCallManager(
    context: Context,
    private val videoEnabledForCall: Boolean,
    private val initiator: Boolean,
    private val localRenderer: SurfaceViewRenderer,
    private val remoteRenderer: SurfaceViewRenderer,
    private val onLocalSdpReady: (String) -> Unit,
    private val onLocalIceCandidate: (IceCandidate) -> Unit,
    private val onConnectionState: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val previousAudioMode = audioManager.mode
    private val previousSpeakerphone = runCatching { audioManager.isSpeakerphoneOn }.getOrDefault(false)

    private val eglBase: EglBase? = if (videoEnabledForCall) EglBase.create() else null
    private val factory: PeerConnectionFactory
    private val peerConnection: PeerConnection

    private var audioTrack: AudioTrack? = null
    private var videoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localSdpDispatched = false
    private var remoteDescriptionReady = false
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private var activeNetwork: Network? = null
    private var restartAttempts = 0
    private var restartScheduled = false
    private var lastRestartAt = 0L
    @Volatile private var disposed = false

    private val cameraEventsHandler = object : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(errorDescription: String) {
            postError("Camera error: $errorDescription")
        }

        override fun onCameraDisconnected() {
            postError("Camera disconnected. End TwoLink and try again.")
        }

        override fun onCameraFreezed(errorDescription: String) {
            postError("Camera stopped responding: $errorDescription")
        }

        override fun onCameraOpening(cameraName: String) = Unit
        override fun onFirstFrameAvailable() = Unit
        override fun onCameraClosed() = Unit
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = activeNetwork
            activeNetwork = network
            if (previous != null && previous != network && initiator) {
                requestIceRestart(delayMs = 500L)
            }
        }

        override fun onLost(network: Network) {
            if (activeNetwork == network) {
                activeNetwork = null
            }
        }
    }

    init {
        ensureFactoryInitialized(appContext)

        val builder = PeerConnectionFactory.builder()
        if (videoEnabledForCall) {
            val eglContext = eglBase?.eglBaseContext
                ?: error("Unable to initialize video rendering")

            localRenderer.init(eglContext, null)
            localRenderer.setMirror(true)
            localRenderer.setEnableHardwareScaler(true)
            remoteRenderer.init(eglContext, null)
            remoteRenderer.setMirror(false)
            remoteRenderer.setEnableHardwareScaler(true)

            builder
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
        }

        factory = builder.createPeerConnectionFactory()

        val rtcConfig = PeerConnection.RTCConfiguration(buildIceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }

        peerConnection = factory.createPeerConnection(rtcConfig, createPeerObserver())
            ?: error("Unable to create WebRTC peer connection")

        activeNetwork = connectivityManager.activeNetwork
        runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        runCatching { audioManager.isSpeakerphoneOn = videoEnabledForCall }

        createLocalTracks()
    }

    private fun createLocalTracks() {
        audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("twoshare-audio", audioSource).also {
            it.setEnabled(true)
            peerConnection.addTrack(it, listOf("twoshare-stream"))
        }

        if (!videoEnabledForCall) return

        val capturer = createCameraCapturer()
        if (capturer == null) {
            postError("No compatible camera is available on this device.")
            return
        }

        val eglContext = eglBase?.eglBaseContext
            ?: error("Video rendering is not available")

        videoCapturer = capturer
        videoSource = factory.createVideoSource(false)
        surfaceTextureHelper = SurfaceTextureHelper.create(
            "TwoLinkCapture-${System.identityHashCode(this)}",
            eglContext
        )
        capturer.initialize(
            surfaceTextureHelper,
            appContext,
            videoSource!!.capturerObserver
        )

        try {
            capturer.startCapture(480, 360, 20)
        } catch (t: Throwable) {
            postError("Unable to start camera: ${t.localizedMessage ?: "camera error"}")
        }

        videoTrack = factory.createVideoTrack("twoshare-video", videoSource).also {
            it.setEnabled(true)
            it.addSink(localRenderer)
            peerConnection.addTrack(it, listOf("twoshare-stream"))
        }
    }

    fun startCaller() {
        if (disposed) return
        localSdpDispatched = false
        postState("Creating TwoLink…")
        createOffer()
    }

    private fun createOffer() {
        if (disposed) return
        peerConnection.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                setLocalDescription(description)
            }

            override fun onCreateFailure(error: String) {
                postError("Unable to create TwoLink offer: $error")
            }
        }, MediaConstraints())
    }

    fun startCallee(offerSdp: String) {
        if (disposed) return
        localSdpDispatched = false
        postState(if (remoteDescriptionReady) "Reconnecting through relay…" else "Answering…")
        peerConnection.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                if (disposed) return
                remoteDescriptionReady = true
                flushPendingRemoteCandidates()
                peerConnection.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(description: SessionDescription) {
                        setLocalDescription(description)
                    }

                    override fun onCreateFailure(error: String) {
                        postError("Unable to create TwoLink answer: $error")
                    }
                }, MediaConstraints())
            }

            override fun onSetFailure(error: String) {
                postError("Unable to apply TwoLink offer: $error")
            }
        }, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
    }

    fun applyAnswer(answerSdp: String) {
        if (disposed) return
        peerConnection.setRemoteDescription(
            object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    if (disposed) return
                    remoteDescriptionReady = true
                    flushPendingRemoteCandidates()
                    postState("Connecting…")
                }

                override fun onSetFailure(error: String) {
                    postError("Unable to apply TwoLink answer: $error")
                }
            },
            SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        )
    }

    private fun setLocalDescription(description: SessionDescription) {
        if (disposed) return
        peerConnection.setLocalDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                dispatchLocalDescription()
            }

            override fun onSetFailure(error: String) {
                postError("Unable to set local TwoLink description: $error")
            }
        }, description)
    }

    private fun dispatchLocalDescription() {
        if (disposed || localSdpDispatched) return
        val description = peerConnection.localDescription ?: return
        localSdpDispatched = true
        mainHandler.post { if (!disposed) onLocalSdpReady(description.description) }
    }

    fun addRemoteIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        if (disposed) return
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        if (!remoteDescriptionReady) {
            pendingRemoteCandidates += iceCandidate
            return
        }
        runCatching { peerConnection.addIceCandidate(iceCandidate) }
    }

    private fun flushPendingRemoteCandidates() {
        if (disposed || !remoteDescriptionReady || pendingRemoteCandidates.isEmpty()) return
        pendingRemoteCandidates.toList().forEach { candidate ->
            runCatching { peerConnection.addIceCandidate(candidate) }
        }
        pendingRemoteCandidates.clear()
    }

    private fun requestIceRestart(delayMs: Long = 900L) {
        if (disposed || !initiator || restartScheduled) return
        val now = System.currentTimeMillis()
        if (now - lastRestartAt < 2_500L) return

        if (restartAttempts >= 3) {
            postError("Unable to reconnect on this network. End TwoLink and call again.")
            return
        }

        restartScheduled = true
        postState("Reconnecting through relay…")
        mainHandler.postDelayed({
            if (disposed) return@postDelayed
            restartScheduled = false
            restartAttempts += 1
            lastRestartAt = System.currentTimeMillis()
            localSdpDispatched = false

            runCatching { peerConnection.restartIce() }
                .onSuccess { createOffer() }
                .onFailure {
                    if (restartAttempts >= 3) {
                        postError("Unable to restart the connection. End TwoLink and call again.")
                    } else {
                        requestIceRestart(1_200L)
                    }
                }
        }, delayMs)
    }

    fun setMuted(muted: Boolean) {
        if (!disposed) audioTrack?.setEnabled(!muted)
    }

    fun setVideoEnabled(enabled: Boolean) {
        if (!disposed) videoTrack?.setEnabled(enabled)
    }

    fun setSpeakerEnabled(enabled: Boolean) {
        if (disposed) return
        runCatching { audioManager.isSpeakerphoneOn = enabled }
            .onFailure { postError("Unable to change speaker routing.") }
    }

    fun switchCamera() {
        if (disposed) return
        try {
            videoCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) = Unit
                override fun onCameraSwitchError(errorDescription: String) {
                    postError("Unable to switch camera: $errorDescription")
                }
            })
        } catch (t: Throwable) {
            postError("Unable to switch camera: ${t.localizedMessage ?: "camera error"}")
        }
    }

    @Synchronized
    fun dispose() {
        if (disposed) return
        disposed = true
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }

        try {
            videoCapturer?.stopCapture()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Throwable) {
            // Continue teardown even if a vendor camera implementation misbehaves.
        }

        runCatching { videoTrack?.removeSink(localRenderer) }
        runCatching { remoteVideoTrack?.removeSink(remoteRenderer) }
        runCatching { peerConnection.close() }
        runCatching { peerConnection.dispose() }

        runCatching { videoCapturer?.dispose() }
        runCatching { videoTrack?.dispose() }
        runCatching { audioTrack?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { audioSource?.dispose() }
        runCatching { surfaceTextureHelper?.dispose() }
        runCatching { factory.dispose() }

        if (videoEnabledForCall) {
            runCatching { localRenderer.release() }
            runCatching { remoteRenderer.release() }
            runCatching { eglBase?.release() }
        }

        runCatching { audioManager.mode = previousAudioMode }
        runCatching { audioManager.isSpeakerphoneOn = previousSpeakerphone }

        videoCapturer = null
        videoTrack = null
        remoteVideoTrack = null
        videoSource = null
        audioTrack = null
        audioSource = null
        surfaceTextureHelper = null
        pendingRemoteCandidates.clear()
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        createFromEnumerator(Camera1Enumerator(true))?.let { return it }

        if (Camera2Enumerator.isSupported(appContext)) {
            createFromEnumerator(Camera2Enumerator(appContext))?.let { return it }
        }
        return null
    }

    private fun createFromEnumerator(enumerator: CameraEnumerator): CameraVideoCapturer? {
        val names = runCatching { enumerator.deviceNames }.getOrDefault(emptyArray())

        names.firstOrNull { name -> runCatching { enumerator.isFrontFacing(name) }.getOrDefault(false) }
            ?.let { name -> runCatching { enumerator.createCapturer(name, cameraEventsHandler) }.getOrNull() }
            ?.let { return it }

        names.firstOrNull { name -> runCatching { enumerator.isBackFacing(name) }.getOrDefault(false) }
            ?.let { name -> runCatching { enumerator.createCapturer(name, cameraEventsHandler) }.getOrNull() }
            ?.let { return it }

        names.firstOrNull()
            ?.let { name -> runCatching { enumerator.createCapturer(name, cameraEventsHandler) }.getOrNull() }
            ?.let { return it }

        return null
    }

    private fun attachRemoteVideo(track: MediaStreamTrack?) {
        if (disposed || !videoEnabledForCall) return
        val video = track as? VideoTrack ?: return
        if (remoteVideoTrack === video) return
        runCatching { remoteVideoTrack?.removeSink(remoteRenderer) }
        remoteVideoTrack = video
        runCatching { video.addSink(remoteRenderer) }
    }

    private fun createPeerObserver(): PeerConnection.Observer = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            if (disposed) return
            when (newState) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    restartAttempts = 0
                    postState("Connected")
                }
                PeerConnection.IceConnectionState.CHECKING -> postState("Connecting…")
                PeerConnection.IceConnectionState.FAILED -> {
                    if (initiator) requestIceRestart() else postState("Waiting for reconnection…")
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    postState("Reconnecting…")
                    if (initiator) requestIceRestart(1_500L)
                }
                else -> Unit
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            if (disposed) return
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    restartAttempts = 0
                    postState("Connected")
                }
                PeerConnection.PeerConnectionState.CONNECTING -> postState("Connecting…")
                PeerConnection.PeerConnectionState.FAILED -> {
                    if (initiator) requestIceRestart() else postState("Waiting for reconnection…")
                }
                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                    postState("Reconnecting…")
                    if (initiator) requestIceRestart(1_500L)
                }
                else -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit

        override fun onIceCandidate(candidate: IceCandidate) {
            mainHandler.post {
                if (!disposed) onLocalIceCandidate(candidate)
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) {
            stream.videoTracks.firstOrNull()?.let { attachRemoteVideo(it) }
        }
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(dataChannel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) {
            attachRemoteVideo(receiver.track())
        }
        override fun onTrack(transceiver: RtpTransceiver) {
            attachRemoteVideo(transceiver.receiver.track())
        }
    }

    private fun postState(state: String) {
        mainHandler.post { if (!disposed) onConnectionState(state) }
    }

    private fun postError(message: String) {
        mainHandler.post { if (!disposed) onError(message) }
    }

    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }

    companion object {
        @Volatile
        private var factoryInitialized = false
        private val factoryInitLock = Any()

        private fun ensureFactoryInitialized(context: Context) {
            if (factoryInitialized) return
            synchronized(factoryInitLock) {
                if (factoryInitialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .createInitializationOptions()
                )
                factoryInitialized = true
            }
        }

        private fun buildIceServers(): List<PeerConnection.IceServer> {
            val expiresAt = Instant.now().epochSecond + (12 * 60 * 60)
            val username = "$expiresAt:twoshare"
            val secret = "openrelayprojectsecret"
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
            val password = Base64.encodeToString(
                mac.doFinal(username.toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP
            )

            fun relay(url: String) = PeerConnection.IceServer.builder(url)
                .setUsername(username)
                .setPassword(password)
                .createIceServer()

            return listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
                relay("turn:staticauth.openrelay.metered.ca:80"),
                relay("turn:staticauth.openrelay.metered.ca:443"),
                relay("turn:staticauth.openrelay.metered.ca:443?transport=tcp"),
                relay("turns:staticauth.openrelay.metered.ca:443?transport=tcp")
            )
        }
    }
}
