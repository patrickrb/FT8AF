import AVFoundation
import FT8DSP

/// Pure connection-lifecycle state for the TX player node, extracted from
/// `TxPlayerService` so the (untestable) `AVAudioEngine` calls stay a thin
/// wrapper around testable decisions.
///
/// The node is *attached* to the engine exactly once, but its *connection*
/// into the mixer graph is torn down by the engine on every
/// `AVAudioEngineConfigurationChange` (a USB audio interface such as a DigiRig
/// attaching/detaching, or any output-route change). Calling
/// `AVAudioPlayerNode.play()` on a node whose connection was invalidated raises
/// an uncaught Obj-C `NSException` inside AVFAudio and aborts the whole app —
/// which is exactly what happened at TX time after a DigiRig was plugged in.
/// Tracking attach and connect separately lets `play()` re-establish the
/// connection lazily after a config change instead of trusting a stale flag.
struct TxConnectionState: Equatable {
    private(set) var isAttached = false
    private(set) var isConnected = false

    /// The node must be attached to the engine before it can be connected.
    var needsAttach: Bool { !isAttached }
    /// The node must be (re)connected + the converter rebuilt before playback.
    var needsConnect: Bool { !isConnected }
    /// Playback preconditions are satisfied — node attached and connected.
    var isReady: Bool { isAttached && isConnected }

    mutating func markAttached() { isAttached = true }
    mutating func markConnected() { isConnected = true }

    /// A configuration change leaves the node attached but invalidates every
    /// connection in the graph, so the next `play()` must reconnect and rebuild
    /// the format converter against the (possibly new) mixer output format.
    mutating func invalidateConnection() { isConnected = false }
}

/// Plays a mono 12 kHz FT8 waveform through the device speaker using an
/// `AVAudioPlayerNode` attached to a shared `AVAudioEngine`. The TX scheduler
/// calls `play(_:)` at the correct slot time; the service handles sample-rate
/// conversion to the hardware output format internally.
///
/// Node attachment is deferred to the first `play()` call so that the output
/// hardware format has stabilized (on Simulator the output node may report
/// 0 Hz at engine-start time, which crashes `connect()`). The connection is
/// re-established after any `AVAudioEngineConfigurationChange` — see
/// `TxConnectionState`.
final class TxPlayerService: @unchecked Sendable {
    private let playerNode = AVAudioPlayerNode()
    private var engine: AVAudioEngine?
    private var outputConverter: AVAudioConverter?
    private let lock = NSLock()
    private var _isPlaying = false
    private var conn = TxConnectionState()

    /// The 12 kHz mono format used by FT8Encoder output.
    private let ft8Format = AVAudioFormat(
        commonFormat: .pcmFormatFloat32,
        sampleRate: Double(FT8.sampleRate),
        channels: 1,
        interleaved: false
    )!

    var isPlaying: Bool {
        lock.lock(); defer { lock.unlock() }
        return _isPlaying
    }

    /// Store the engine reference for deferred attachment and observe
    /// configuration changes so a route change (e.g. a DigiRig attaching)
    /// forces the player node to reconnect before the next TX. Does NOT connect
    /// the player node yet — that happens lazily in `ensureConnected()`.
    func configure(engine: AVAudioEngine) {
        self.engine = engine
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleConfigChange),
            name: .AVAudioEngineConfigurationChange,
            object: engine
        )
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    /// The engine tore down its graph (route/config change). The node stays
    /// attached but its connection is gone; drop the cached converter so the
    /// next `play()` reconnects against the current mixer format instead of
    /// keying a disconnected node (which aborts the app).
    @objc private func handleConfigChange() {
        lock.lock()
        conn.invalidateConnection()
        outputConverter = nil
        lock.unlock()
    }

    /// Attach (once) and connect the player node, creating the format
    /// converter. Reconnects and rebuilds the converter when a prior config
    /// change invalidated the connection. Returns `false` if the hardware
    /// output format is invalid (e.g. 0 Hz on Simulator at startup) — the
    /// caller should skip playback.
    private func ensureConnected() -> Bool {
        lock.lock(); defer { lock.unlock() }
        if !conn.needsConnect { return outputConverter != nil }
        guard let engine else { return false }

        // Use the mixer's output format — on real devices this is the
        // hardware rate (e.g. 48 kHz stereo); on Simulator it becomes valid
        // once the engine is running. After a config change it may be a
        // different rate (e.g. the DigiRig's), so it must be re-read here.
        let mixerFormat = engine.mainMixerNode.outputFormat(forBus: 0)
        guard mixerFormat.sampleRate > 0, mixerFormat.channelCount > 0 else {
            return false
        }

        if conn.needsAttach {
            engine.attach(playerNode)
            conn.markAttached()
        }
        engine.connect(playerNode, to: engine.mainMixerNode, format: mixerFormat)
        outputConverter = AVAudioConverter(from: ft8Format, to: mixerFormat)
        guard outputConverter != nil else { return false }
        conn.markConnected()
        return true
    }

    /// Schedule and play `samples` (mono Float32 at 12 kHz). Interrupts any
    /// previously playing buffer so a new TX overrides the old one.
    ///
    /// Returns `true` if playback was actually started, `false` if it bailed
    /// before keying any audio (invalid hardware output format, buffer allocation,
    /// or a conversion failure). On every bail path `completion` is still invoked,
    /// so callers that only care about teardown can ignore the result — but the
    /// caller must not treat a `false` return as "the audio went out".
    @discardableResult
    func play(_ samples: [Float], completion: (() -> Void)? = nil) -> Bool {
        // The shared engine can be stopped at play time (route/config change,
        // interruption, Simulator startup) — AVAudioPlayerNode.play() on a
        // stopped engine logs "Cannot play yet!" and the TX is silent, and a
        // stopped engine can also report an invalid mixer format, which would
        // make the deferred connect below bail. Restart it first.
        if let engine, !engine.isRunning {
            do {
                try engine.start()
            } catch {
                completion?()
                return false
            }
        }

        // Re-establish the node connection if a config change invalidated it.
        // Skipping this is what aborts the app: play() on a disconnected node
        // raises an uncaught NSException in AVFAudio.
        guard ensureConnected(), let converter = outputConverter else {
            completion?()
            return false
        }

        // Wrap input samples in a PCM buffer at 12 kHz.
        let frameCount = AVAudioFrameCount(samples.count)
        guard let inputBuffer = AVAudioPCMBuffer(pcmFormat: ft8Format, frameCapacity: frameCount) else {
            completion?()
            return false
        }
        inputBuffer.frameLength = frameCount
        samples.withUnsafeBufferPointer { src in
            inputBuffer.floatChannelData![0].update(from: src.baseAddress!, count: samples.count)
        }

        // Convert to the hardware output format.
        let outputFormat = converter.outputFormat
        let ratio = outputFormat.sampleRate / ft8Format.sampleRate
        let outFrames = AVAudioFrameCount(Double(frameCount) * ratio) + 1
        guard let outputBuffer = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: outFrames) else {
            completion?()
            return false
        }

        var error: NSError?
        var consumed = false
        converter.convert(to: outputBuffer, error: &error) { _, outStatus in
            if consumed {
                outStatus.pointee = .noDataNow
                return nil
            }
            consumed = true
            outStatus.pointee = .haveData
            return inputBuffer
        }
        if error != nil {
            completion?()
            return false
        }

        lock.lock()
        _isPlaying = true
        lock.unlock()

        playerNode.stop()
        playerNode.scheduleBuffer(outputBuffer, at: nil, options: .interrupts) { [weak self] in
            self?.lock.lock()
            self?._isPlaying = false
            self?.lock.unlock()
            completion?()
        }
        playerNode.play()
        return true
    }

    /// Cancel any pending/active playback.
    func stop() {
        playerNode.stop()
        lock.lock()
        _isPlaying = false
        lock.unlock()
    }
}
