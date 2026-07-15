import AVFoundation
import FT8DSP

/// Plays a mono 12 kHz FT8 waveform through the device speaker using an
/// `AVAudioPlayerNode` attached to a shared `AVAudioEngine`. The TX scheduler
/// calls `play(_:)` at the correct slot time; the service handles sample-rate
/// conversion to the hardware output format internally.
///
/// Node attachment is deferred to the first `play()` call so that the output
/// hardware format has stabilized (on Simulator the output node may report
/// 0 Hz at engine-start time, which crashes `connect()`).
final class TxPlayerService: @unchecked Sendable {
    private let playerNode = AVAudioPlayerNode()
    private var engine: AVAudioEngine?
    private var outputConverter: AVAudioConverter?
    private let lock = NSLock()
    private var _isPlaying = false
    private var isAttached = false

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

    /// Store the engine reference for deferred attachment. Does NOT connect
    /// the player node yet — that happens lazily in `ensureAttached()`.
    func configure(engine: AVAudioEngine) {
        self.engine = engine
    }

    /// Attach and connect the player node, creating the format converter.
    /// Returns `false` if the hardware output format is invalid (e.g. 0 Hz
    /// on Simulator at startup) — the caller should skip playback.
    private func ensureAttached() -> Bool {
        if isAttached { return outputConverter != nil }
        guard let engine else { return false }

        // Use the mixer's output format — on real devices this is the
        // hardware rate (e.g. 48 kHz stereo); on Simulator it becomes valid
        // once the engine is running.
        let mixerFormat = engine.mainMixerNode.outputFormat(forBus: 0)
        guard mixerFormat.sampleRate > 0, mixerFormat.channelCount > 0 else {
            return false
        }

        engine.attach(playerNode)
        engine.connect(playerNode, to: engine.mainMixerNode, format: mixerFormat)
        outputConverter = AVAudioConverter(from: ft8Format, to: mixerFormat)
        isAttached = true
        return outputConverter != nil
    }

    /// Schedule and play `samples` (mono Float32 at 12 kHz). Interrupts any
    /// previously playing buffer so a new TX overrides the old one.
    func play(_ samples: [Float], completion: (() -> Void)? = nil) {
        // The shared engine can be stopped at play time (route/config change,
        // interruption, Simulator startup) — AVAudioPlayerNode.play() on a
        // stopped engine logs "Cannot play yet!" and the TX is silent, and a
        // stopped engine can also report an invalid mixer format, which would
        // make the deferred attach below bail. Restart it first.
        if let engine, !engine.isRunning {
            do {
                try engine.start()
            } catch {
                completion?()
                return
            }
        }

        guard ensureAttached(), let converter = outputConverter else {
            completion?()
            return
        }

        // Wrap input samples in a PCM buffer at 12 kHz.
        let frameCount = AVAudioFrameCount(samples.count)
        guard let inputBuffer = AVAudioPCMBuffer(pcmFormat: ft8Format, frameCapacity: frameCount) else {
            completion?()
            return
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
            return
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
            return
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
    }

    /// Cancel any pending/active playback.
    func stop() {
        playerNode.stop()
        lock.lock()
        _isPlaying = false
        lock.unlock()
    }
}
