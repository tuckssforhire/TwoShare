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
    private val onConnectionState: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val previousAudioMode = audioManager.mode
    private val previousSpeakerphone = audioManager.isSpeakerphoneOn

    private val eglBase = EglBase.create()
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
    private var disposed = false

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .createInitializationOptions()
        )

        localRenderer.init(eglBase.eglBaseContext, null)
        localRenderer.setMirror(true)
        remoteRenderer.init(eglBase.eglBaseContext, null)
        remoteRenderer.setMirror(false)

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext,
            true,
            true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }

        peerConnection = factory.createPeerConnection(rtcConfig, createPeerObserver())
            ?: error("Unable to create WebRTC peer connection")

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = videoEnabledForCall

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
            postError("No camera is available on this device.")
            return
        }

        videoCapturer = capturer
        videoSource = factory.createVideoSource(false)
        surfaceTextureHelper = SurfaceTextureHelper.create(
            "TwoShareCaptureThread",
            eglBase.eglBaseContext
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
        peerConnection.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                setLocalDescription(description)
            }
        }, MediaConstraints())
    }

    fun startCallee(offerSdp: String) {
        localSdpDispatched = false
        peerConnection.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                peerConnection.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(description: SessionDescription) {
                        setLocalDescription(description)
                    }
                }, MediaConstraints())
            }
        }, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
    }

    fun applyAnswer(answerSdp: String) {
        peerConnection.setRemoteDescription(
            object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    postState("Connecting")
                }
            },
            SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        )
    }

    private fun setLocalDescription(description: SessionDescription) {
        peerConnection.setLocalDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                if (peerConnection.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
                    dispatchLocalDescription()
                } else {
                    mainHandler.postDelayed({ dispatchLocalDescription() }, 8_000)
                }
            }
        }, description)
    }

    private fun dispatchLocalDescription() {
        if (disposed || localSdpDispatched) return
        val description = peerConnection.localDescription ?: return
        localSdpDispatched = true
        mainHandler.post { onLocalSdpReady(description.description) }
    }

    fun setMuted(muted: Boolean) {
        audioTrack?.setEnabled(!muted)
    }

    fun setVideoEnabled(enabled: Boolean) {
        videoTrack?.setEnabled(enabled)
    }

    fun setSpeakerEnabled(enabled: Boolean) {
        audioManager.isSpeakerphoneOn = enabled
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        mainHandler.removeCallbacksAndMessages(null)

        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        runCatching { videoTrack?.removeSink(localRenderer) }
        runCatching { remoteVideoTrack?.removeSink(remoteRenderer) }
        runCatching { videoTrack?.dispose() }
        runCatching { audioTrack?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { audioSource?.dispose() }
        runCatching { surfaceTextureHelper?.dispose() }
        runCatching { peerConnection.close() }
        runCatching { peerConnection.dispose() }
        runCatching { factory.dispose() }
        runCatching { localRenderer.release() }
        runCatching { remoteRenderer.release() }
        runCatching { eglBase.release() }

        audioManager.mode = previousAudioMode
        audioManager.isSpeakerphoneOn = previousSpeakerphone
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(appContext)
        val names = enumerator.deviceNames

        names.firstOrNull { enumerator.isFrontFacing(it) }?.let { name ->
            enumerator.createCapturer(name, null)?.let { return it }
        }
        names.firstOrNull()?.let { name ->
            enumerator.createCapturer(name, null)?.let { return it }
        }
        return null
    }

    private fun attachRemoteVideo(track: MediaStreamTrack?) {
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
                PeerConnection.IceConnectionState.CHECKING -> postState("Connecting")
                PeerConnection.IceConnectionState.FAILED -> postError("Call connection failed.")
                PeerConnection.IceConnectionState.DISCONNECTED -> postState("Reconnecting")
                else -> Unit
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> postState("Connected")
                PeerConnection.PeerConnectionState.CONNECTING -> postState("Connecting")
                PeerConnection.PeerConnectionState.FAILED -> postError("Call connection failed.")
                PeerConnection.PeerConnectionState.DISCONNECTED -> postState("Reconnecting")
                else -> Unit
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                dispatchLocalDescription()
            }
        }

        override fun onIceCandidate(candidate: IceCandidate) = Unit
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
}
