import AVFoundation
import FT8DSP

/// Plays a mono 12 kHz FT8 waveform through the device speaker using an
/// `AVAudioPlayerNode` attached to a shared `AVAudioEngine`. The TX scheduler
/// calls `play(_:)` at the correct slot time; the service handles sample-rate
/// conversion to the hardware output format internally.
final class TxPlayerService: @unchecked Sendable {
    private let playerNode = AVAudioPlayerNode()
    private var engine: AVAudioEngine?
    private var outputConverter: AVAudioConverter?
    private let lock = NSLock()
    private var _isPlaying = false

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

    /// Attach the player node to the shared `AVAudioEngine` (the same one
    /// `AudioCaptureService` owns). Call once at setup before any `play()`.
    func configure(engine: AVAudioEngine) {
        self.engine = engine
        engine.attach(playerNode)
        let outputFormat = engine.outputNode.outputFormat(forBus: 0)
        engine.connect(playerNode, to: engine.mainMixerNode, format: outputFormat)
        outputConverter = AVAudioConverter(from: ft8Format, to: outputFormat)
    }

    /// Schedule and play `samples` (mono Float32 at 12 kHz). Interrupts any
    /// previously playing buffer so a new TX overrides the old one.
    func play(_ samples: [Float], completion: (() -> Void)? = nil) {
        guard let converter = outputConverter else { return }

        // Wrap input samples in a PCM buffer at 12 kHz.
        let frameCount = AVAudioFrameCount(samples.count)
        guard let inputBuffer = AVAudioPCMBuffer(pcmFormat: ft8Format, frameCapacity: frameCount) else { return }
        inputBuffer.frameLength = frameCount
        samples.withUnsafeBufferPointer { src in
            inputBuffer.floatChannelData![0].update(from: src.baseAddress!, count: samples.count)
        }

        // Convert to the hardware output format.
        let outputFormat = converter.outputFormat
        let ratio = outputFormat.sampleRate / ft8Format.sampleRate
        let outFrames = AVAudioFrameCount(Double(frameCount) * ratio) + 1
        guard let outputBuffer = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: outFrames) else { return }

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
        if error != nil { return }

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
