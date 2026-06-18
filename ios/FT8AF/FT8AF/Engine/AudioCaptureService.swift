import AVFoundation
import FT8Audio
import FT8DSP

/// Captures microphone audio via AVAudioEngine, downsamples to 12 kHz mono,
/// and pushes samples into a `SlotAccumulator` for the decode and waterfall
/// pipelines.
final class AudioCaptureService: @unchecked Sendable {
    let accumulator = SlotAccumulator()

    private let engine = AVAudioEngine()
    private var converter: AVAudioConverter?
    private var _isRunning = false
    private let lock = NSLock()

    var isRunning: Bool {
        lock.lock(); defer { lock.unlock() }
        return _isRunning
    }

    /// Request microphone permission. Returns `true` if granted.
    func requestPermission() async -> Bool {
        await withCheckedContinuation { cont in
            AVAudioApplication.requestRecordPermission { granted in
                cont.resume(returning: granted)
            }
        }
    }

    /// Configure the audio session, install a tap on the input node, and start
    /// capturing. Throws if the audio session or engine fails to start.
    func start() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, options: [.defaultToSpeaker, .allowBluetooth])
        try session.setActive(true)

        let inputNode = engine.inputNode
        let hwFormat = inputNode.outputFormat(forBus: 0)

        // Target: 12 kHz, mono, Float32 — the FT8 codec sample rate.
        guard let targetFormat = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: Double(FT8.sampleRate),
            channels: 1,
            interleaved: false
        ) else {
            throw AudioCaptureError.formatCreationFailed
        }

        guard let conv = AVAudioConverter(from: hwFormat, to: targetFormat) else {
            throw AudioCaptureError.converterCreationFailed
        }
        converter = conv

        // Install tap at the hardware format; we downsample in the callback.
        let bufferSize = AVAudioFrameCount(hwFormat.sampleRate * 0.1) // ~100 ms chunks
        inputNode.installTap(onBus: 0, bufferSize: bufferSize, format: hwFormat) {
            [weak self] buffer, _ in
            self?.handleAudioBuffer(buffer)
        }

        try engine.start()

        lock.lock()
        _isRunning = true
        lock.unlock()

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleInterruption),
            name: AVAudioSession.interruptionNotification,
            object: nil
        )
    }

    /// Stop the audio engine and remove the tap.
    func stop() {
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        converter = nil

        lock.lock()
        _isRunning = false
        lock.unlock()

        NotificationCenter.default.removeObserver(
            self,
            name: AVAudioSession.interruptionNotification,
            object: nil
        )
    }

    // MARK: - Private

    private func handleAudioBuffer(_ buffer: AVAudioPCMBuffer) {
        guard let conv = converter else { return }

        // Allocate output buffer for the downsampled result.
        let ratio = Double(FT8.sampleRate) / buffer.format.sampleRate
        let outFrames = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 1
        guard let outBuffer = AVAudioPCMBuffer(
            pcmFormat: conv.outputFormat,
            frameCapacity: outFrames
        ) else { return }

        var error: NSError?
        var consumed = false
        conv.convert(to: outBuffer, error: &error) { _, outStatus in
            if consumed {
                outStatus.pointee = .noDataNow
                return nil
            }
            consumed = true
            outStatus.pointee = .haveData
            return buffer
        }

        if error != nil { return }
        guard let channelData = outBuffer.floatChannelData,
              outBuffer.frameLength > 0 else { return }

        let samples = Array(UnsafeBufferPointer(
            start: channelData[0],
            count: Int(outBuffer.frameLength)
        ))
        accumulator.push(samples)
    }

    @objc private func handleInterruption(_ notification: Notification) {
        guard let info = notification.userInfo,
              let typeValue = info[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else { return }

        switch type {
        case .began:
            lock.lock()
            _isRunning = false
            lock.unlock()
        case .ended:
            let options = (info[AVAudioSessionInterruptionOptionKey] as? UInt)
                .flatMap(AVAudioSession.InterruptionOptions.init) ?? []
            if options.contains(.shouldResume) {
                try? engine.start()
                lock.lock()
                _isRunning = true
                lock.unlock()
            }
        @unknown default:
            break
        }
    }
}

enum AudioCaptureError: Error {
    case formatCreationFailed
    case converterCreationFailed
}
