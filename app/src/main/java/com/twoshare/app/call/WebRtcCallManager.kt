package com.twoshare.app.call

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import org.webrtc.*

class WebRtcCallManager(
    context: Context,
    private val videoEnabledForCall: Boolean,
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
    private var disposed = false

    init {
        ensureFactoryInitialized(appContext)

        val builder = PeerConnectionFactory.builder()
        if (videoEnabledForCall) {
            val eglContext = eglBase?.eglBaseContext
                ?: error("Unable to initialize video rendering")

            localRenderer.init(eglContext, null)
            localRenderer.setMirror(true)
            remoteRenderer.init(eglContext, null)
            remoteRenderer.setMirror(false)

            builder
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
        }

        factory = builder.createPeerConnectionFactory()

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory.createPeerConnection(rtcConfig, createPeerObserver())
            ?: error("Unable to create WebRTC peer connection")

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
            "TwoShareCaptureThread",
            eglContext
        )
        capturer.initialize(
            surfaceTextureHelper,
            appContext,
            videoSource!!.capturerObserver
        )
        runCatching { capturer.startCapture(640, 480, 24) }
            .onFailure { postError("Unable to start camera: ${it.localizedMessage ?: "camera error"}") }

        videoTrack = factory.createVideoTrack("twoshare-video", videoSource).also {
            it.setEnabled(true)
            it.addSink(localRenderer)
            peerConnection.addTrack(it, listOf("twoshare-stream"))
        }
    }

    fun startCaller() {
        localSdpDispatched = false
        postState("Creating call…")
        peerConnection.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                setLocalDescription(description)
            }

            override fun onCreateFailure(error: String) {
                postError("Unable to create call offer: $error")
            }
        }, MediaConstraints())
    }

    fun startCallee(offerSdp: String) {
        localSdpDispatched = false
        postState("Answering…")
        peerConnection.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                remoteDescriptionReady = true
                flushPendingRemoteCandidates()
                peerConnection.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(description: SessionDescription) {
                        setLocalDescription(description)
                    }

                    override fun onCreateFailure(error: String) {
                        postError("Unable to create call answer: $error")
                    }
                }, MediaConstraints())
            }

            override fun onSetFailure(error: String) {
                postError("Unable to apply call offer: $error")
            }
        }, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
    }

    fun applyAnswer(answerSdp: String) {
        peerConnection.setRemoteDescription(
            object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    remoteDescriptionReady = true
                    flushPendingRemoteCandidates()
                    postState("Connecting…")
                }

                override fun onSetFailure(error: String) {
                    postError("Unable to apply call answer: $error")
                }
            },
            SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        )
    }

    private fun setLocalDescription(description: SessionDescription) {
        peerConnection.setLocalDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                dispatchLocalDescription()
            }

            override fun onSetFailure(error: String) {
                postError("Unable to set local call description: $error")
            }
        }, description)
    }

    private fun dispatchLocalDescription() {
        if (disposed || localSdpDispatched) return
        val description = peerConnection.localDescription ?: return
        localSdpDispatched = true
        mainHandler.post { onLocalSdpReady(description.description) }
    }

    fun addRemoteIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        if (disposed) return
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        if (!remoteDescriptionReady) {
            pendingRemoteCandidates += iceCandidate
            return
        }
        peerConnection.addIceCandidate(iceCandidate)
    }

    private fun flushPendingRemoteCandidates() {
        if (!remoteDescriptionReady || pendingRemoteCandidates.isEmpty()) return
        pendingRemoteCandidates.toList().forEach { peerConnection.addIceCandidate(it) }
        pendingRemoteCandidates.clear()
    }

    fun setMuted(muted: Boolean) {
        audioTrack?.setEnabled(!muted)
    }

    fun setVideoEnabled(enabled: Boolean) {
        videoTrack?.setEnabled(enabled)
    }

    fun setSpeakerEnabled(enabled: Boolean) {
        runCatching { audioManager.isSpeakerphoneOn = enabled }
            .onFailure { postError("Unable to change speaker routing.") }
    }

    fun switchCamera() {
        runCatching { videoCapturer?.switchCamera(null) }
            .onFailure { postError("Unable to switch camera.") }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        mainHandler.removeCallbacksAndMessages(null)

        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        if (videoEnabledForCall) {
            runCatching { videoTrack?.removeSink(localRenderer) }
            runCatching { remoteVideoTrack?.removeSink(remoteRenderer) }
        }
        runCatching { videoTrack?.dispose() }
        runCatching { audioTrack?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { audioSource?.dispose() }
        runCatching { surfaceTextureHelper?.dispose() }
        runCatching { peerConnection.close() }
        runCatching { peerConnection.dispose() }
        runCatching { factory.dispose() }
        if (videoEnabledForCall) {
            runCatching { localRenderer.release() }
            runCatching { remoteRenderer.release() }
            runCatching { eglBase?.release() }
        }

        audioManager.mode = previousAudioMode
        runCatching { audioManager.isSpeakerphoneOn = previousSpeakerphone }
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(appContext)) {
            Camera2Enumerator(appContext)
        } else {
            Camera1Enumerator(true)
        }

        val names = enumerator.deviceNames
        names.firstOrNull { enumerator.isFrontFacing(it) }?.let { name ->
            enumerator.createCapturer(name, null)?.let { return it }
        }
        names.firstOrNull { enumerator.isBackFacing(it) }?.let { name ->
            enumerator.createCapturer(name, null)?.let { return it }
        }
        names.firstOrNull()?.let { name ->
            enumerator.createCapturer(name, null)?.let { return it }
        }
        return null
    }

    private fun attachRemoteVideo(track: MediaStreamTrack?) {
        if (!videoEnabledForCall) return
        val video = track as? VideoTrack ?: return
        if (remoteVideoTrack === video) return
        remoteVideoTrack?.removeSink(remoteRenderer)
        remoteVideoTrack = video
        video.addSink(remoteRenderer)
    }

    private fun createPeerObserver(): PeerConnection.Observer = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            when (newState) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> postState("Connected")
                PeerConnection.IceConnectionState.CHECKING -> postState("Connecting…")
                PeerConnection.IceConnectionState.FAILED -> postError("Call connection failed. A TURN relay may be required on this network.")
                PeerConnection.IceConnectionState.DISCONNECTED -> postState("Reconnecting…")
                else -> Unit
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> postState("Connected")
                PeerConnection.PeerConnectionState.CONNECTING -> postState("Connecting…")
                PeerConnection.PeerConnectionState.FAILED -> postError("Call connection failed. A TURN relay may be required on this network.")
                PeerConnection.PeerConnectionState.DISCONNECTED -> postState("Reconnecting…")
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
    }
}
