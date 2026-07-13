import FT8DSP
import FT8Engine
import Foundation
import Network

/// Thin app-side transport for the online-logging integrations. All request
/// building and response parsing lives in FT8Engine (CloudlogClient,
/// QrzLogbookClient, PskReporterEncoder — pure and unit-tested); this class
/// just moves the bytes: URLSession for the HTTP APIs and an NWConnection UDP
/// socket for PSK Reporter.
///
/// Mirrors Android's split between `ThirdPartyService` (Cloudlog/QRZ upload +
/// catch-up sync with per-record synced flags) and `PskReporterSender`
/// (batched spots flushed every ~5 minutes with a per-call/band dedup window).
@Observable @MainActor
final class OnlineLogService {
    static let shared = OnlineLogService()

    // Catch-up sync progress (drives the Logbook toolbar UI).
    private(set) var isSyncing = false
    private(set) var syncDone = 0
    private(set) var syncTotal = 0

    // PSK Reporter batching state.
    private let pskEncoder = PskReporterEncoder()
    private let pskDedup = PskDedup()
    private var pskQueue: [PskSpot] = []
    private var pskFlushTask: Task<Void, Never>?

    // MARK: - Upload on QSO logged

    /// Fire-and-forget upload of a freshly-logged QSO to every enabled
    /// service (each attempt retried once), then mark the record's synced
    /// flags and persist. Safe to call unconditionally — no-op when nothing
    /// is enabled/configured.
    func handleQsoLogged(_ record: QsoRecord, appState: AppState) {
        let s = appState.settings
        let cloudlog = s.cloudlogEnabled && !s.cloudlogUrl.isEmpty && !s.cloudlogApiKey.isEmpty
        let qrz = s.qrzLogbookEnabled && !s.qrzLogbookApiKey.isEmpty
        guard cloudlog || qrz else { return }

        let adif = Adif.singleRecord(record)
        let cloudlogReq = cloudlog
            ? CloudlogClient.qsoUploadRequest(
                serverAddress: s.cloudlogUrl, apiKey: s.cloudlogApiKey,
                stationId: s.cloudlogStationId, adif: adif)
            : nil
        let qrzBody = qrz ? QrzLogbookClient.insertBody(apiKey: s.qrzLogbookApiKey, adif: adif) : nil

        Task { [weak appState] in
            var clOk = false
            var qrzOk = false
            if let cloudlogReq { clOk = await Self.postCloudlogWithRetry(cloudlogReq) }
            if let qrzBody { qrzOk = await Self.postQrzWithRetry(qrzBody) }
            guard clOk || qrzOk, let appState else { return }
            Self.markSynced(id: record.id, cloudlog: clOk, qrz: qrzOk, appState: appState)
            QsoLogStore.save(appState.logbook.records)
        }
    }

    private static func markSynced(id: Int64?, cloudlog: Bool, qrz: Bool, appState: AppState) {
        guard let id,
              let idx = appState.logbook.records.firstIndex(where: { $0.id == id })
        else { return }
        if cloudlog { appState.logbook.records[idx].syncedCloudlog = true }
        if qrz { appState.logbook.records[idx].syncedQrz = true }
    }

    // MARK: - Catch-up sync ("sync all unsynced")

    /// Re-upload every record still awaiting an enabled service (services
    /// dedupe by call+date+time+mode, so repeats are safe). Updates
    /// `syncDone`/`syncTotal` as it goes and persists the flags at the end.
    /// Returns (attempted, cloudlogOk, qrzOk).
    func syncAll(appState: AppState) async -> (attempted: Int, cloudlogOk: Int, qrzOk: Int) {
        guard !isSyncing else { return (0, 0, 0) }
        let s = appState.settings
        let cl = s.cloudlogEnabled && !s.cloudlogUrl.isEmpty && !s.cloudlogApiKey.isEmpty
        let qrz = s.qrzLogbookEnabled && !s.qrzLogbookApiKey.isEmpty
        let pending = OnlineLogSync.unsynced(
            appState.logbook.records, cloudlogEnabled: cl, qrzEnabled: qrz)
        guard !pending.isEmpty else { return (0, 0, 0) }

        isSyncing = true
        syncTotal = pending.count
        syncDone = 0
        var clOk = 0
        var qrzOk = 0
        for record in pending {
            let adif = Adif.singleRecord(record)
            if cl, !record.syncedCloudlog,
               let req = CloudlogClient.qsoUploadRequest(
                   serverAddress: s.cloudlogUrl, apiKey: s.cloudlogApiKey,
                   stationId: s.cloudlogStationId, adif: adif),
               await Self.postCloudlog(req) {
                clOk += 1
                Self.markSynced(id: record.id, cloudlog: true, qrz: false, appState: appState)
            }
            if qrz, !record.syncedQrz {
                let body = QrzLogbookClient.insertBody(apiKey: s.qrzLogbookApiKey, adif: adif)
                if await Self.postQrz(body) {
                    qrzOk += 1
                    Self.markSynced(id: record.id, cloudlog: false, qrz: true, appState: appState)
                }
            }
            syncDone += 1
        }
        QsoLogStore.save(appState.logbook.records)
        isSyncing = false
        return (pending.count, clOk, qrzOk)
    }

    // MARK: - Test connections

    /// Cloudlog/Wavelog `api/auth/<key>` check. The URL carries the key —
    /// never log it.
    nonisolated static func testCloudlog(serverAddress: String, apiKey: String) async -> Bool {
        guard let urlStr = CloudlogClient.authUrl(serverAddress: serverAddress, apiKey: apiKey),
              let url = URL(string: urlStr)
        else { return false }
        var req = URLRequest(url: url)
        req.timeoutInterval = 15
        do {
            let (data, resp) = try await URLSession.shared.data(for: req)
            guard let http = resp as? HTTPURLResponse, http.statusCode == 200 else { return false }
            return CloudlogClient.isAuthValid(String(data: data, encoding: .utf8))
        } catch {
            return false
        }
    }

    /// QRZ logbook `ACTION=STATUS` check (POST keeps the key out of the URL).
    nonisolated static func testQrz(apiKey: String) async -> Bool {
        guard !apiKey.isEmpty else { return false }
        let response = await postForm(QrzLogbookClient.statusBody(apiKey: apiKey))
        return QrzLogbookClient.isStatusOk(response)
    }

    // MARK: - PSK Reporter

    /// Hand this slot's decodes to the reporter queue. Applies the Android
    /// sender's policy (skip self/free-text/unresolved hashes; one report per
    /// callsign per band per 5 minutes) and schedules a batched flush.
    func enqueuePskDecodes(_ decoded: [DecodedMessage], dialFreqHz: Int64, appState: AppState) {
        let s = appState.settings
        guard s.pskReporterEnabled, !s.myCall.isEmpty, !decoded.isEmpty else { return }
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        for m in decoded {
            guard let spot = PskReporter.makeSpot(
                callFrom: m.callFrom, i3: m.i3, n3: m.n3, grid: m.grid, snr: m.snr,
                audioFreqHz: m.freqHz, dialFreqHz: dialFreqHz,
                myCall: s.myCall, utcSeconds: nowMs / 1000)
            else { continue }
            guard pskDedup.markIfFresh(
                call: spot.senderCallsign, dialFreqHz: dialFreqHz, nowMs: nowMs)
            else { continue }
            pskQueue.append(spot)
        }
        scheduleFlushIfNeeded(appState: appState)
    }

    private func scheduleFlushIfNeeded(appState: AppState) {
        guard pskFlushTask == nil, !pskQueue.isEmpty else { return }
        pskFlushTask = Task { [weak self, weak appState] in
            try? await Task.sleep(for: .milliseconds(Int(PskReporter.sendIntervalMs)))
            guard let self, !Task.isCancelled else { return }
            self.pskFlushTask = nil
            self.flushPsk(appState: appState)
        }
    }

    /// Build the IPFIX datagrams for the queued spots and send them.
    func flushPsk(appState: AppState?) {
        guard !pskQueue.isEmpty, let appState else { return }
        let myCall = appState.settings.myCall
        guard !myCall.isEmpty else { return }
        let spots = pskQueue
        pskQueue.removeAll()
        let version = (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "1.0"
        let packets = pskEncoder.buildPackets(
            rxCall: myCall,
            rxGrid: appState.settings.myGrid,
            software: PskReporter.buildSoftwareString(version: version, rigName: nil),
            spots: spots,
            nowMs: Int64(Date().timeIntervalSince1970 * 1000))
        Self.sendUdp(packets.map { Data($0) })
    }

    private nonisolated static func sendUdp(_ packets: [Data]) {
        guard !packets.isEmpty,
              let port = NWEndpoint.Port(rawValue: PskReporter.port)
        else { return }
        let conn = NWConnection(
            host: NWEndpoint.Host(PskReporter.host), port: port, using: .udp)
        conn.stateUpdateHandler = { state in
            switch state {
            case .ready:
                // Sends complete in order on the connection queue; tear the
                // connection down once the last one is processed.
                for (i, packet) in packets.enumerated() {
                    conn.send(content: packet, completion: .contentProcessed { _ in
                        if i == packets.count - 1 { conn.cancel() }
                    })
                }
            case .failed:
                conn.cancel() // fire-and-forget: drop the batch
            default:
                break
            }
        }
        conn.start(queue: .global(qos: .utility))
    }

    // MARK: - HTTP plumbing

    private static func postCloudlogWithRetry(_ req: CloudlogClient.Request) async -> Bool {
        if await postCloudlog(req) { return true }
        try? await Task.sleep(for: .seconds(2))
        return await postCloudlog(req)
    }

    private static func postQrzWithRetry(_ body: String) async -> Bool {
        if await postQrz(body) { return true }
        try? await Task.sleep(for: .seconds(2))
        return await postQrz(body)
    }

    /// POST the JSON upload. URLSession transparently follows the 307/308
    /// redirects Wavelog/Nextlog emit for rewritten paths, preserving the
    /// POST body (unlike Android's HttpURLConnection, which needs the manual
    /// redirect walk).
    private nonisolated static func postCloudlog(_ req: CloudlogClient.Request) async -> Bool {
        guard let url = URL(string: req.url) else { return false }
        var r = URLRequest(url: url)
        r.httpMethod = "POST"
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = Data(req.body.utf8)
        r.timeoutInterval = 20
        do {
            let (_, resp) = try await URLSession.shared.data(for: r)
            guard let http = resp as? HTTPURLResponse else { return false }
            return CloudlogClient.isSuccessStatus(http.statusCode)
        } catch {
            return false
        }
    }

    private nonisolated static func postQrz(_ body: String) async -> Bool {
        QrzLogbookClient.isInsertSuccess(await postForm(body))
    }

    /// POST a form body to the QRZ endpoint; returns the response text on
    /// HTTP 200/201, nil otherwise.
    private nonisolated static func postForm(_ body: String) async -> String? {
        guard let url = URL(string: QrzLogbookClient.endpoint) else { return nil }
        var r = URLRequest(url: url)
        r.httpMethod = "POST"
        r.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        r.httpBody = Data(body.utf8)
        r.timeoutInterval = 20
        do {
            let (data, resp) = try await URLSession.shared.data(for: r)
            guard let http = resp as? HTTPURLResponse,
                  http.statusCode == 200 || http.statusCode == 201
            else { return nil }
            return String(data: data, encoding: .utf8)
        } catch {
            return nil
        }
    }
}
