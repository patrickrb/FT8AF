import Foundation

/// A station that called us while we were busy with another QSO (or running a
/// CQ), waiting its turn (port of Android's `QueuedCaller`).
public struct QueuedCaller: Equatable, Sendable {
    public let callsign: String
    public var snr: Int
    public var freqHz: Float
    public var grid: String
    /// When the station was last heard calling us (refreshes on re-hear).
    public var queuedAtMs: Int64

    public init(callsign: String, snr: Int, freqHz: Float, grid: String, queuedAtMs: Int64) {
        self.callsign = callsign
        self.snr = snr
        self.freqHz = freqHz
        self.grid = grid
        self.queuedAtMs = queuedAtMs
    }
}

/// Pure FIFO caller-queue policy (port of the Android `callerQueue` logic in
/// `FT8TransmitSignal`): dedup by callsign (a re-heard caller refreshes its
/// SNR/frequency/timestamp but keeps its place), a hard capacity cap, and a
/// staleness rule — a caller not re-heard for a few slots has moved on and is
/// pruned rather than called into dead air.
public struct CallerQueue: Equatable, Sendable {
    /// Maximum queued callers (Android `MAX_QUEUE_SIZE`).
    public static let maxSize = 10
    /// Drop callers not re-heard within this window (~4 FT8 slots — a station
    /// still interested re-calls every other slot).
    public static let staleAfterMs: Int64 = 60_000

    public private(set) var callers: [QueuedCaller] = []

    public init() {}

    public var isEmpty: Bool { callers.isEmpty }
    public var count: Int { callers.count }

    /// Whether a heard station belongs in the queue at all: never ourselves,
    /// never the station we are already working, never a blocked callsign.
    public static func shouldEnqueue(
        callsign: String,
        myCall: String,
        currentTarget: String?,
        blocked: [String]
    ) -> Bool {
        let call = callsign.uppercased()
        guard !call.isEmpty else { return false }
        if call == myCall.uppercased() { return false }
        if let target = currentTarget, call == target.uppercased() { return false }
        if blocked.contains(where: { $0.uppercased() == call }) { return false }
        return true
    }

    /// Add or refresh a caller. Dedup is by callsign: an existing entry keeps
    /// its queue position but takes the new SNR/frequency/grid/timestamp. A new
    /// caller is appended only while under capacity.
    /// - Returns: `true` if the queue now contains the caller.
    @discardableResult
    public mutating func enqueue(_ caller: QueuedCaller) -> Bool {
        let call = caller.callsign.uppercased()
        if let idx = callers.firstIndex(where: { $0.callsign.uppercased() == call }) {
            callers[idx].snr = caller.snr
            callers[idx].freqHz = caller.freqHz
            if !caller.grid.isEmpty { callers[idx].grid = caller.grid }
            callers[idx].queuedAtMs = caller.queuedAtMs
            return true
        }
        guard callers.count < Self.maxSize else { return false }
        callers.append(caller)
        return true
    }

    /// Drop callers last heard more than `staleAfterMs` ago.
    public mutating func removeStale(nowMs: Int64) {
        callers.removeAll { nowMs - $0.queuedAtMs > Self.staleAfterMs }
    }

    /// Pop the head of the queue (oldest caller first).
    public mutating func dequeueNext() -> QueuedCaller? {
        callers.isEmpty ? nil : callers.removeFirst()
    }

    /// Pop a specific caller by callsign (case-insensitive).
    public mutating func dequeue(callsign: String) -> QueuedCaller? {
        let call = callsign.uppercased()
        guard let idx = callers.firstIndex(where: { $0.callsign.uppercased() == call }) else {
            return nil
        }
        return callers.remove(at: idx)
    }

    /// Remove a caller without returning it (e.g. the engine locked them on
    /// its own, so their queue slot is obsolete).
    public mutating func remove(callsign: String) {
        let call = callsign.uppercased()
        callers.removeAll { $0.callsign.uppercased() == call }
    }

    public mutating func removeAll() {
        callers.removeAll()
    }
}
