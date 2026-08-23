import AVFoundation
import FT8Audio
import FT8DSP

/// Captures microphone audio via AVAudioEngine, downsamples to 12 kHz mono,
/// and pushes samples into a `SlotAccumulator` for the decode and waterfall
/// pipelines.
final class AudioCaptureService: @unchecked Sendable {
    let accumulator = SlotAccumulator()

    /// The underlying `AVAudioEngine`. Exposed so `TxPlayerService` can attach
    /// its player node to the same engine for audio output.
    let audioEngine = AVAudioEngine()

    private var engine: AVAudioEngine { audioEngine }
    private var converter: AVAudioConverter?
    private var _isRunning = false
    private let lock = NSLock()

    /// Output buffer reused across tap callbacks so the real-time audio thread
    /// doesn't heap-allocate a fresh `AVAudioPCMBuffer` on every chunk. Touched
    /// only on the tap thread (the tap is serialized); grown if a larger input
    /// chunk ever arrives.
    private var reusableOutBuffer: AVAudioPCMBuffer?

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
        // Observers first, so the route change that activation itself emits
        // (e.g. iOS picking up an already-attached DigiRig) is never missed.
        addObservers()
        do {
            // Bootstrap: under the default playback category the session lists
            // no inputs at all, so `usbAudioPresent` would read a plugged-in
            // DigiRig as absent and pin output to the speaker — TX then never
            // reaches the radio. Put the session into `.playAndRecord` with the
            // neutral (no speaker pin) options and activate it, and only THEN
            // evaluate the USB-dependent policy against real port lists.
            if session.category != .playAndRecord {
                try session.setCategory(
                    .playAndRecord,
                    options: Self.categoryOptions(from: AudioSessionPolicy.bootstrapOptions)
                )
            }
            try session.setActive(true)
            try applySessionCategory(session)

            try installTapAndStart()
        } catch {
            removeObservers()
            throw error
        }
    }

    private func addObservers() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleInterruption),
            name: AVAudioSession.interruptionNotification,
            object: nil
        )
        // A DigiRig (or any USB audio interface) attach/detach must flip the
        // output-routing policy: with USB present we drop `.defaultToSpeaker`
        // so TX audio follows to the interface (feeding the radio's Data-VOX),
        // and restore it when USB goes away so a bare device still plays RX
        // audibly. Re-apply the category on every route change.
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleRouteChange),
            name: AVAudioSession.routeChangeNotification,
            object: nil
        )
        // The engine stops itself when the audio hardware configuration
        // changes (output/input route change, Simulator host-device switch)
        // and the input format may change with it. Without rebuilding the
        // tap and restarting, RX goes silently deaf while the UI still
        // says RX.
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleConfigChange),
            name: .AVAudioEngineConfigurationChange,
            object: engine
        )
    }

    private func removeObservers() {
        NotificationCenter.default.removeObserver(
            self,
            name: AVAudioSession.interruptionNotification,
            object: nil
        )
        NotificationCenter.default.removeObserver(
            self,
            name: .AVAudioEngineConfigurationChange,
            object: engine
        )
        NotificationCenter.default.removeObserver(
            self,
            name: AVAudioSession.routeChangeNotification,
            object: nil
        )
    }

    /// (Re)build the converter for the current hardware input format, install
    /// the tap, and start the engine. Used at startup and again after every
    /// engine configuration change.
    private func installTapAndStart() throws {
        let inputNode = engine.inputNode
        let hwFormat = inputNode.outputFormat(forBus: 0)
        guard hwFormat.sampleRate > 0, hwFormat.channelCount > 0 else {
            throw AudioCaptureError.formatCreationFailed
        }

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
        lock.lock()
        converter = conv
        lock.unlock()

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
    }

    /// Stop the audio engine and remove the tap.
    func stop() {
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()

        lock.lock()
        converter = nil
        _isRunning = false
        lock.unlock()

        removeObservers()
    }

    // MARK: - Session routing policy

    /// Configure the shared session's `.playAndRecord` category with the
    /// output-routing policy that suits the current hardware: speaker on a bare
    /// device, follow-the-USB-interface when one is attached (see
    /// `AudioSessionPolicy`). Also clears a lingering speaker override when USB
    /// audio is present so TX reaches the interface.
    private func applySessionCategory(_ session: AVAudioSession) throws {
        let usb = Self.usbAudioPresent(in: session)
        let options = Self.categoryOptions(from:
            AudioSessionPolicy.playAndRecordOptions(usbAudioConnected: usb))

        // Only re-set the category when it actually changes — re-setting it
        // emits a `.categoryChange` route-change notification, which would
        // re-enter this method and loop.
        if session.category != .playAndRecord || session.categoryOptions != options {
            try session.setCategory(.playAndRecord, options: options)
        }

        // With USB active, undo any leftover forced-speaker override so output
        // falls back to the interface. Guarded on "currently stuck on speaker"
        // so we never disturb an explicit route-picker choice.
        if usb,
           session.currentRoute.outputs.contains(where: { $0.portType == .builtInSpeaker }) {
            try? session.overrideOutputAudioPort(.none)
        }
    }

    /// True when a USB audio interface (DigiRig etc.) is attached, as either a
    /// selectable input or the active input/output route. Only meaningful once
    /// the session is in an input-capable category (see `start()`); the
    /// decision itself lives in `AudioSessionPolicy.usbAudioPresent` so every
    /// detection path is host-tested.
    static func usbAudioPresent(in session: AVAudioSession) -> Bool {
        let route = session.currentRoute
        return AudioSessionPolicy.usbAudioPresent(
            availableInputs: (session.availableInputs ?? []).map { AudioRouteController.kind(of: $0.portType) },
            routeInputs: route.inputs.map { AudioRouteController.kind(of: $0.portType) },
            routeOutputs: route.outputs.map { AudioRouteController.kind(of: $0.portType) }
        )
    }

    /// Map the platform-neutral routing decision onto AVFoundation's option set.
    static func categoryOptions(from opts: PlayAndRecordOption) -> AVAudioSession.CategoryOptions {
        var result: AVAudioSession.CategoryOptions = []
        if opts.contains(.defaultToSpeaker) { result.insert(.defaultToSpeaker) }
        if opts.contains(.allowBluetooth) { result.insert(.allowBluetooth) }
        return result
    }

    @objc private func handleRouteChange(_ notification: Notification) {
        // Ignore the notifications our own category/override changes emit, to
        // avoid re-entering `applySessionCategory` in a loop.
        if let reasonValue = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
           let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue),
           reason == .categoryChange || reason == .override {
            return
        }
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            try? self.applySessionCategory(AVAudioSession.sharedInstance())
        }
    }

    // MARK: - Private

    private func handleAudioBuffer(_ buffer: AVAudioPCMBuffer) {
        // Copy the converter to a local strong reference under the lock — `stop()`
        // may clear it from the main thread while this runs on the audio thread.
        lock.lock()
        let conv = converter
        lock.unlock()
        guard let conv else { return }

        // Reuse the output buffer across callbacks; only (re)allocate when none
        // exists yet or the input chunk grew beyond the current capacity.
        let ratio = Double(FT8.sampleRate) / buffer.format.sampleRate
        let outFrames = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 1
        if reusableOutBuffer == nil || reusableOutBuffer!.frameCapacity < outFrames {
            guard let buf = AVAudioPCMBuffer(
                pcmFormat: conv.outputFormat,
                frameCapacity: outFrames
            ) else { return }
            reusableOutBuffer = buf
        }
        guard let outBuffer = reusableOutBuffer else { return }
        outBuffer.frameLength = 0

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

        // Hand samples straight to the accumulator without an intermediate
        // `[Float]` allocation on the real-time thread.
        accumulator.push(UnsafeBufferPointer(
            start: channelData[0],
            count: Int(outBuffer.frameLength)
        ))
    }

    @objc private func handleConfigChange(_ notification: Notification) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.lock.lock()
            let wasRunning = self._isRunning
            self.lock.unlock()
            guard wasRunning else { return }

            self.engine.inputNode.removeTap(onBus: 0)
            // The tap is gone, so the audio thread can't touch the reusable
            // buffer; drop it in case the chunk geometry changes.
            self.reusableOutBuffer = nil
            try? self.installTapAndStart()
        }
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
