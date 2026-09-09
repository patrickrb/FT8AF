import FT8Engine
import SwiftUI
import UIKit

enum PotaTab: String, CaseIterable, Identifiable {
    case activate = "Activate"
    case hunt = "Hunt"
    case history = "History"

    var id: String { rawValue }
}

struct PotaScreen: View {
    @Environment(AppState.self) private var appState
    @State private var selectedTab: PotaTab = .hunt
    @State private var shareURLs: [URL] = []
    @State private var showShare = false
    @State private var showParkPicker = false
    /// True while a self-spot POST is in flight (drives the button spinner and
    /// prevents double-posts). One user tap per activation — never automatic.
    @State private var isSelfSpotting = false
    /// True while an authenticated ADIF upload is in flight (button spinner).
    @State private var isUploading = false
    /// Presents the hosted-UI OAuth WebView. Set when an upload needs sign-in.
    @State private var showLogin = false
    /// The activation to (re-)upload once sign-in completes.
    @State private var pendingUploadActivation: PotaActivationRecord?

    private var spotService: PotaService { PotaService.shared }

    var body: some View {
        VStack(spacing: 0) {
            // Top bar
            HStack {
                Text("POTA")
                    .font(.ft8afUI(size: 18, weight: .bold))
                    .foregroundStyle(textPrimary)

                Spacer()

                if appState.pota.isActivating {
                    HStack(spacing: 4) {
                        Circle()
                            .fill(statusConfirmed)
                            .frame(width: 6, height: 6)
                        Text("ACTIVE")
                            .font(.ft8afMono(size: 10, weight: .bold))
                            .foregroundStyle(statusConfirmed)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, 8)

            // Sub-tab picker
            Picker("", selection: $selectedTab) {
                ForEach(PotaTab.allCases) { tab in
                    Text(tab.rawValue).tag(tab)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            .padding(.bottom, 12)

            // Tab content
            Group {
                switch selectedTab {
                case .activate: activateTab
                case .hunt:     huntTab
                case .history:  historyTab
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(bgApp)
        .onAppear(perform: loadActivationsIfNeeded)
        .task(id: selectedTab) {
            // Auto-refresh the live spot feed every ~60 s while the Hunt tab is
            // visible (Android's PotaSpotsRepository cadence). The task is
            // cancelled automatically when the tab changes or the screen leaves.
            guard selectedTab == .hunt else { return }
            while !Task.isCancelled {
                await spotService.refresh()
                try? await Task.sleep(nanoseconds: 60_000_000_000)
            }
        }
        .sheet(isPresented: $showShare) {
            PotaShareSheet(items: shareURLs)
        }
        .sheet(isPresented: $showLogin) {
            PotaLoginSheet { success in
                showLogin = false
                // On a successful sign-in, resume the upload that triggered it.
                if success, let activation = pendingUploadActivation {
                    uploadActivation(activation)
                }
                pendingUploadActivation = nil
            }
        }
        .sheet(isPresented: $showParkPicker) {
            ParkPickerSheet(
                myGrid: appState.settings.myGrid,
                recentRefs: recentParkRefs
            ) { reference in
                appState.pota.parkInput = reference
            }
        }
    }

    /// Park references from activation history, newest first, for the picker's
    /// "Recent" section. Each record's `parkRef` may be comma-joined (two-fers);
    /// the picker splits + dedupes via `PotaParks.deduplicateRefs`.
    private var recentParkRefs: [String] {
        appState.pota.activations
            .sorted { $0.startedAtMs > $1.startedAtMs }
            .map(\.parkRef)
    }

    // MARK: - Activation persistence

    private func loadActivationsIfNeeded() {
        guard !appState.pota.activationsLoaded else { return }
        appState.pota.activations = PotaActivationStore.load()
        appState.pota.activationsLoaded = true
    }

    private func persistActivations() {
        PotaActivationStore.save(appState.pota.activations)
    }

    // MARK: - Activate Tab

    @ViewBuilder
    private var activateTab: some View {
        if let current = appState.pota.current {
            activeSessionView(current: current)
        } else {
            startActivationView
        }
    }

    private var startActivationView: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "tree.fill")
                .font(.ft8afUI(size: 40))
                .foregroundStyle(statusConfirmed.opacity(0.5))
            Text("Park Activation")
                .font(.ft8afUI(size: 16, weight: .semibold))
                .foregroundStyle(textPrimary)
            Text("Enter a POTA park reference (e.g. US-1234)\nto start an activation session")
                .font(.ft8afUI(size: 13))
                .foregroundStyle(textMuted)
                .multilineTextAlignment(.center)

            // Park reference input
            VStack(spacing: 8) {
                HStack {
                    Text("Park Ref:")
                        .font(.ft8afUI(size: 13, weight: .medium))
                        .foregroundStyle(textMuted)
                    TextField("US-0000", text: Bindable(appState.pota).parkInput)
                        .font(.ft8afMono(size: 14, weight: .medium))
                        .foregroundStyle(textPrimary)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 8)
                        .background(
                            RoundedRectangle(cornerRadius: 8)
                                .fill(bgSurface3)
                        )
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                }
                .padding(.horizontal, 40)

                // Search parks (recent + nearby picker); free-text entry above
                // remains the fallback.
                Button {
                    showParkPicker = true
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "magnifyingglass")
                            .font(.ft8afUI(size: 12, weight: .semibold))
                        Text("Search parks")
                            .font(.ft8afUI(size: 13, weight: .semibold))
                    }
                    .foregroundStyle(accent)
                    .frame(maxWidth: .infinity)
                    .frame(height: 38)
                    .background(
                        RoundedRectangle(cornerRadius: 10)
                            .fill(accent.opacity(0.12))
                            .overlay(
                                RoundedRectangle(cornerRadius: 10)
                                    .strokeBorder(accent.opacity(0.3), lineWidth: 1)
                            )
                    )
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 40)

                // Notes field
                HStack {
                    Text("Notes:")
                        .font(.ft8afUI(size: 13, weight: .medium))
                        .foregroundStyle(textMuted)
                    TextField("Optional notes", text: Bindable(appState.pota).notes)
                        .font(.ft8afUI(size: 13))
                        .foregroundStyle(textPrimary)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 8)
                        .background(
                            RoundedRectangle(cornerRadius: 8)
                                .fill(bgSurface3)
                        )
                }
                .padding(.horizontal, 40)
            }

            Button {
                startActivation()
            } label: {
                Text("Start Activation")
                    .font(.ft8afUI(size: 14, weight: .bold))
                    .foregroundStyle(bgApp)
                    .frame(width: 200, height: 44)
                    .background(
                        RoundedRectangle(cornerRadius: 10)
                            .fill(trimmedParkInput.isEmpty ? textFaint : statusConfirmed)
                    )
            }
            .buttonStyle(.plain)
            .disabled(trimmedParkInput.isEmpty)

            Spacer()
        }
    }

    private func activeSessionView(current: PotaActivationRecord) -> some View {
        let qsoCount = activationQsoCount(current)

        return VStack(spacing: 16) {
            // Activation card
            VStack(spacing: 12) {
                HStack {
                    Image(systemName: "tree.fill")
                        .font(.ft8afUI(size: 16))
                        .foregroundStyle(statusConfirmed)
                    Text(current.parkRefsDisplay)
                        .font(.ft8afMono(size: 18, weight: .bold))
                        .foregroundStyle(statusConfirmed)
                    Spacer()
                }

                if !current.notes.isEmpty {
                    Text(current.notes)
                        .font(.ft8afUI(size: 12))
                        .foregroundStyle(textMuted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                Divider().overlay(borderSubtle)

                // Stats row (elapsed ticks once a second)
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    HStack(spacing: 0) {
                        activationStat(
                            label: "QSOs",
                            value: "\(qsoCount)",
                            color: qsoCount >= 10 ? statusConfirmed : accent
                        )
                        activationStat(
                            label: "Needed",
                            value: "\(max(0, 10 - qsoCount))",
                            color: qsoCount >= 10 ? statusConfirmed : statusWarn
                        )
                        activationStat(
                            label: "Elapsed",
                            value: elapsedString(startedAtMs: current.startedAtMs, now: context.date),
                            color: textMuted
                        )
                    }
                }

                // Progress bar to 10 QSOs
                VStack(spacing: 4) {
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            RoundedRectangle(cornerRadius: 3)
                                .fill(bgSurface3)
                            RoundedRectangle(cornerRadius: 3)
                                .fill(qsoCount >= 10 ? statusConfirmed : accent)
                                .frame(width: geo.size.width * min(CGFloat(qsoCount) / 10.0, 1.0))
                        }
                    }
                    .frame(height: 6)

                    Text(qsoCount >= 10 ? "Activation complete!" : "\(qsoCount)/10 for valid activation")
                        .font(.ft8afMono(size: 10, weight: .medium))
                        .foregroundStyle(qsoCount >= 10 ? statusConfirmed : textFaint)
                }
            }
            .padding(16)
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(bgSurface2)
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .strokeBorder(statusConfirmed.opacity(0.3), lineWidth: 1)
                    )
            )
            .padding(.horizontal, 16)

            // Self-spot to pota.app so hunters can find this activation. Explicit
            // user action only (never automatic); posts the current dial + FT8
            // for every park in the activation.
            Button {
                selfSpot(for: current)
            } label: {
                HStack(spacing: 6) {
                    if isSelfSpotting {
                        ProgressView()
                            .controlSize(.small)
                            .tint(accent)
                    } else {
                        Image(systemName: "antenna.radiowaves.left.and.right")
                            .font(.ft8afUI(size: 13, weight: .semibold))
                    }
                    Text(isSelfSpotting ? "Spotting…" : "Self-Spot")
                        .font(.ft8afUI(size: 14, weight: .bold))
                }
                .foregroundStyle(isSelfSpotting ? textFaint : accent)
                .frame(maxWidth: .infinity)
                .frame(height: 44)
                .background(
                    RoundedRectangle(cornerRadius: 10)
                        .fill(accent.opacity(isSelfSpotting ? 0.05 : 0.14))
                        .overlay(
                            RoundedRectangle(cornerRadius: 10)
                                .strokeBorder(accent.opacity(isSelfSpotting ? 0.1 : 0.3), lineWidth: 1)
                        )
                )
            }
            .buttonStyle(.plain)
            .disabled(isSelfSpotting)
            .padding(.horizontal, 16)

            // Upload to POTA (authenticated). Signs in via the hosted-UI WebView
            // first when there's no usable token, then uploads one file per park.
            Button {
                uploadActivation(current)
            } label: {
                HStack(spacing: 6) {
                    if isUploading {
                        ProgressView()
                            .controlSize(.small)
                            .tint(bgApp)
                    } else {
                        Image(systemName: "arrow.up.circle.fill")
                            .font(.ft8afUI(size: 13, weight: .semibold))
                    }
                    Text(isUploading ? "Uploading…" : "Upload to POTA")
                        .font(.ft8afUI(size: 14, weight: .bold))
                }
                .foregroundStyle(bgApp)
                .frame(maxWidth: .infinity)
                .frame(height: 44)
                .background(
                    RoundedRectangle(cornerRadius: 10)
                        .fill(accent.opacity(qsoCount > 0 ? 1 : 0.4))
                )
            }
            .buttonStyle(.plain)
            .disabled(qsoCount == 0 || isUploading)
            .padding(.horizontal, 16)

            // Export ADIF (share sheet) — one file per park, MY_SIG_INFO pinned.
            Button {
                exportAdif(for: current)
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "square.and.arrow.up")
                        .font(.ft8afUI(size: 13, weight: .semibold))
                    Text("Export ADIF")
                        .font(.ft8afUI(size: 14, weight: .bold))
                }
                .foregroundStyle(qsoCount > 0 ? signal : textFaint)
                .frame(maxWidth: .infinity)
                .frame(height: 44)
                .background(
                    RoundedRectangle(cornerRadius: 10)
                        .fill(signal.opacity(qsoCount > 0 ? 0.14 : 0.05))
                        .overlay(
                            RoundedRectangle(cornerRadius: 10)
                                .strokeBorder(signal.opacity(qsoCount > 0 ? 0.3 : 0.1), lineWidth: 1)
                        )
                )
            }
            .buttonStyle(.plain)
            .disabled(qsoCount == 0)
            .padding(.horizontal, 16)

            // End activation button
            Button {
                endActivation()
            } label: {
                Text("End Activation")
                    .font(.ft8afUI(size: 14, weight: .bold))
                    .foregroundStyle(statusBad)
                    .frame(maxWidth: .infinity)
                    .frame(height: 44)
                    .background(
                        RoundedRectangle(cornerRadius: 10)
                            .fill(statusBad.opacity(0.14))
                            .overlay(
                                RoundedRectangle(cornerRadius: 10)
                                    .strokeBorder(statusBad.opacity(0.3), lineWidth: 1)
                            )
                    )
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 16)

            Spacer()
        }
        .padding(.top, 8)
    }

    private func activationStat(label: String, value: String, color: Color) -> some View {
        VStack(spacing: 4) {
            Text(value)
                .font(.ft8afMono(size: 22, weight: .bold))
                .foregroundStyle(color)
            Text(label)
                .font(.ft8afMono(size: 10, weight: .medium))
                .foregroundStyle(textFaint)
        }
        .frame(maxWidth: .infinity)
    }

    private var trimmedParkInput: String {
        appState.pota.parkInput.trimmingCharacters(in: .whitespaces)
    }

    /// Live QSO count for an activation, derived from the logbook records that
    /// fall inside its time window (the same `PotaQsoWindow` scoping the ADIF
    /// export uses). Reading `logbook.records` here means Observation re-renders
    /// this screen whenever a QSO is logged — no manual increments to miss.
    private func activationQsoCount(_ activation: PotaActivationRecord) -> Int {
        PotaQsoWindow.qsoCount(
            records: appState.logbook.records,
            startedAtMs: activation.startedAtMs,
            endedAtMs: activation.endedAtMs
        )
    }

    private func startActivation() {
        let parkRef = trimmedParkInput.uppercased()
        guard !parkRef.isEmpty, appState.pota.current == nil else { return }
        let record = PotaActivationRecord(
            parkRef: parkRef,
            notes: appState.pota.notes.trimmingCharacters(in: .whitespaces),
            startedAtMs: Int64(Date().timeIntervalSince1970 * 1000)
        )
        appState.pota.activations.append(record)
        persistActivations()
        appState.pota.parkInput = ""
        appState.pota.notes = ""
    }

    private func endActivation() {
        guard let current = appState.pota.current,
              let idx = appState.pota.activations.firstIndex(where: { $0.id == current.id })
        else { return }
        appState.pota.activations[idx].endedAtMs = Int64(Date().timeIntervalSince1970 * 1000)
        // Freeze the derived count into the record for the History list.
        appState.pota.activations[idx].qsoCount = activationQsoCount(appState.pota.activations[idx])
        persistActivations()
    }

    private func elapsedString(startedAtMs: Int64, now: Date) -> String {
        let start = Date(timeIntervalSince1970: Double(startedAtMs) / 1000.0)
        let elapsed = max(0, Int(now.timeIntervalSince(start)))
        let hrs = elapsed / 3600
        let mins = (elapsed % 3600) / 60
        if hrs > 0 {
            return "\(hrs):\(String(format: "%02d", mins))"
        }
        return "\(mins)m"
    }

    // MARK: - Self-spot

    /// Post a POTA self-spot for the active activation so hunters can find it.
    /// Mirrors Android's `onSelfSpot`: requires a callsign, spots every park ref
    /// at the current dial + FT8, and toasts the outcome. Only reachable from the
    /// active-session view, so an activation is always in progress.
    private func selfSpot(for activation: PotaActivationRecord) {
        guard !isSelfSpotting else { return }
        let myCall = appState.settings.myCall.trimmingCharacters(in: .whitespaces)
        guard !myCall.isEmpty else {
            appState.toast.show("Set your callsign in Settings first", icon: "exclamationmark.triangle")
            return
        }
        let refs = activation.parkRefs
        guard !refs.isEmpty else { return }
        let freqKhz = PotaSelfSpot.frequencyKhz(forBand: appState.settings.band)

        isSelfSpotting = true
        Task {
            let outcome = await PotaSelfSpotService.shared.selfSpotAll(
                activator: myCall,
                spotter: myCall,
                frequencyKhz: freqKhz,
                mode: "FT8",
                references: refs)
            isSelfSpotting = false
            if outcome.successCount == outcome.total {
                let msg = outcome.total == 1
                    ? "Spotted on pota.app"
                    : "Spotted \(outcome.successCount) parks on pota.app"
                appState.toast.show(msg, icon: "antenna.radiowaves.left.and.right")
            } else if outcome.successCount > 0 {
                appState.toast.show(
                    "Spotted \(outcome.successCount)/\(outcome.total) parks",
                    icon: "exclamationmark.triangle")
            } else {
                appState.toast.show("Self-spot failed", icon: "exclamationmark.triangle")
            }
        }
    }

    // MARK: - ADIF export

    /// Build the per-park ADIF documents for an activation and hand them to the
    /// system share sheet. Multi-park ("two-fer") activations share one file per
    /// park, each with MY_SIG_INFO pinned to that single park.
    private func exportAdif(for activation: PotaActivationRecord) {
        let docs = Adif.potaActivationExport(
            records: appState.logbook.records, activation: activation)
        guard !docs.isEmpty else {
            appState.toast.show("No QSOs in this activation yet", icon: "tree")
            return
        }
        var urls: [URL] = []
        for doc in docs {
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent(doc.filename)
            do {
                try Data(doc.content.utf8).write(to: url, options: .atomic)
                urls.append(url)
            } catch {
                appState.toast.show("Export failed", icon: "exclamationmark.triangle")
                return
            }
        }
        shareURLs = urls
        showShare = true
    }

    // MARK: - Authenticated upload

    /// Build the per-park ADIF documents and upload each to POTA's authenticated
    /// `/adif` endpoint. Mirrors Android's `uploadActivation` + `startUpload`
    /// flow: a missing/revoked token (or a 401) presents the hosted-UI login
    /// WebView and, on success, resumes this upload. Failures are classified into
    /// a user-facing toast.
    private func uploadActivation(_ activation: PotaActivationRecord) {
        guard !isUploading else { return }
        let docs = Adif.potaActivationExport(
            records: appState.logbook.records, activation: activation)
        guard !docs.isEmpty else {
            appState.toast.show("No QSOs in this activation yet", icon: "tree")
            return
        }

        isUploading = true
        Task {
            guard let token = await PotaAuthService.shared.idToken() else {
                // Never signed in / refresh token revoked — prompt for login and
                // resume this upload when it succeeds.
                isUploading = false
                promptLogin(for: activation)
                return
            }

            var uploaded = 0
            var firstFailure: PotaUpload.FailureKind?
            var needsLogin = false
            for doc in docs {
                let outcome = await PotaUploadService.shared.uploadAdif(
                    idToken: token, filename: doc.filename, adif: doc.content)
                switch outcome {
                case .success:
                    uploaded += 1
                case .unauthorized:
                    needsLogin = true
                case .failure(let kind):
                    if firstFailure == nil { firstFailure = kind }
                }
                if needsLogin { break }
            }
            isUploading = false

            if needsLogin {
                promptLogin(for: activation)
            } else if uploaded == docs.count {
                let msg = docs.count == 1
                    ? "Uploaded to pota.app"
                    : "Uploaded \(uploaded) parks to pota.app"
                appState.toast.show(msg, icon: "checkmark.circle")
            } else {
                appState.toast.show(uploadFailureMessage(firstFailure),
                                    icon: "exclamationmark.triangle")
            }
        }
    }

    private func promptLogin(for activation: PotaActivationRecord) {
        pendingUploadActivation = activation
        showLogin = true
    }

    /// Map an upload failure to a user-facing message, mirroring Android's
    /// `UploadFailureKind` strings.
    private func uploadFailureMessage(_ kind: PotaUpload.FailureKind?) -> String {
        switch kind {
        case .busy:
            return "POTA is busy — try again in a moment"
        case .serverError:
            return "POTA rejected the log — check your park reference"
        case .network:
            return "Upload failed — check your connection"
        case .other, nil:
            return "Upload failed"
        }
    }

    // MARK: - Hunt Tab

    private var huntTab: some View {
        VStack(spacing: 0) {
            // Header: spot count + FT8-only toggle
            HStack {
                Text(huntHeaderText)
                    .font(.ft8afUI(size: 12))
                    .foregroundStyle(textMuted)
                Spacer()
                Button {
                    spotService.ft8Only.toggle()
                } label: {
                    Text("FT8 ONLY")
                        .font(.ft8afMono(size: 10, weight: .bold))
                        .foregroundStyle(spotService.ft8Only ? accent : textFaint)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(
                            RoundedRectangle(cornerRadius: 5)
                                .fill(spotService.ft8Only ? accent.opacity(0.14) : bgSurface2)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 5)
                                        .strokeBorder(
                                            spotService.ft8Only ? accent.opacity(0.4) : borderSubtle,
                                            lineWidth: 1)
                                )
                        )
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 8)

            if let error = spotService.lastError {
                HStack(spacing: 6) {
                    Image(systemName: "wifi.exclamationmark")
                        .font(.ft8afUI(size: 11))
                    Text(error)
                        .font(.ft8afUI(size: 11, weight: .medium))
                    Spacer()
                }
                .foregroundStyle(statusWarn)
                .padding(.horizontal, 16)
                .padding(.vertical, 6)
                .background(statusWarn.opacity(0.08))
            }

            if spotService.displaySpots.isEmpty {
                VStack(spacing: 12) {
                    Spacer()
                    if spotService.isLoading && spotService.lastUpdatedAt == nil {
                        ProgressView()
                            .tint(accent)
                        Text("Loading live spots…")
                            .font(.ft8afUI(size: 13))
                            .foregroundStyle(textMuted)
                    } else {
                        Image(systemName: "antenna.radiowaves.left.and.right.slash")
                            .font(.ft8afUI(size: 40))
                            .foregroundStyle(textFaint)
                        Text("No active spots")
                            .font(.ft8afUI(size: 16, weight: .medium))
                            .foregroundStyle(textMuted)
                        Text("Live POTA activator spots will appear here")
                            .font(.ft8afUI(size: 13))
                            .foregroundStyle(textFaint)
                    }
                    Spacer()
                }
            } else {
                // List (not ScrollView) so pull-to-refresh works natively.
                TimelineView(.periodic(from: .now, by: 30)) { context in
                    List {
                        ForEach(spotService.displaySpots) { spot in
                            PotaSpotRow(spot: spot, now: context.date) {
                                targetSpot(spot)
                            }
                            .listRowInsets(EdgeInsets())
                            .listRowBackground(bgApp)
                            .listRowSeparator(.hidden)
                        }
                        // Bottom spacer so the TX strip doesn't cover the last row.
                        Color.clear
                            .frame(height: 100)
                            .listRowBackground(bgApp)
                            .listRowSeparator(.hidden)
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                    .refreshable {
                        await spotService.refresh()
                    }
                }
            }
        }
    }

    private var huntHeaderText: String {
        let n = spotService.displaySpots.count
        if n == 0 { return spotService.lastUpdatedAt == nil ? "Live POTA spots" : "No active spots" }
        return "\(n) active spot\(n == 1 ? "" : "s")"
    }

    /// Tap-to-hunt: for an FT8 spot on a known band, switch the app's band the
    /// same way the TX strip's band chip does (settings.band + persist — the
    /// engine reads the dial from settings), then confirm with a toast. Non-FT8
    /// or out-of-band spots just get an informational toast (the operator tunes
    /// manually, as on Android).
    private func targetSpot(_ spot: PotaSpot) {
        UISelectionFeedbackGenerator().selectionChanged()
        let band = PotaSpots.band(forFrequencyKhz: spot.frequencyKhz)
        if spot.isFT8, let band {
            if appState.settings.band != band {
                appState.settings.band = band
                SettingsPersistence.save(appState.settings)
            }
            appState.toast.show(
                "Targeting \(spot.activator) · \(band) (\(spot.reference))",
                icon: "scope")
        } else {
            appState.toast.show(
                "\(spot.activator) at \(spot.frequencyText) \(spot.mode) — tune manually",
                icon: "dot.radiowaves.left.and.right")
        }
    }

    // MARK: - History Tab

    private var historyTab: some View {
        let history = potaHistoryForDisplay(appState.pota.activations)
        return Group {
            if history.isEmpty {
                VStack(spacing: 12) {
                    Spacer()
                    Image(systemName: "clock.arrow.circlepath")
                        .font(.ft8afUI(size: 40))
                        .foregroundStyle(textFaint)
                    Text("No activations yet")
                        .font(.ft8afUI(size: 16, weight: .medium))
                        .foregroundStyle(textMuted)
                    Text("Past POTA activations will appear here")
                        .font(.ft8afUI(size: 13))
                        .foregroundStyle(textFaint)
                    Spacer()
                }
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(history) { activation in
                            historyRow(activation)
                        }
                    }
                    .padding(.bottom, 100)
                }
            }
        }
    }

    private func historyRow(_ activation: PotaActivationRecord) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "tree.fill")
                .font(.ft8afUI(size: 12))
                .foregroundStyle(statusConfirmed.opacity(0.6))

            VStack(alignment: .leading, spacing: 2) {
                Text(activation.parkRefsDisplay)
                    .font(.ft8afMono(size: 14, weight: .bold))
                    .foregroundStyle(textPrimary)
                Text(historyDateText(activation))
                    .font(.ft8afUI(size: 11))
                    .foregroundStyle(textFaint)
            }

            Spacer()

            Text("\(activation.qsoCount) QSO\(activation.qsoCount == 1 ? "" : "s")")
                .font(.ft8afMono(size: 11, weight: .semibold))
                .foregroundStyle(activation.qsoCount >= 10 ? statusConfirmed : textMuted)

            if activation.qsoCount > 0 {
                Button {
                    uploadActivation(activation)
                } label: {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.ft8afUI(size: 14, weight: .semibold))
                        .foregroundStyle(accent)
                        .frame(width: 34, height: 34)
                        .background(
                            RoundedRectangle(cornerRadius: 8)
                                .fill(accent.opacity(0.14))
                        )
                }
                .buttonStyle(.plain)
                .disabled(isUploading)
            }

            Button {
                exportAdif(for: activation)
            } label: {
                Image(systemName: "square.and.arrow.up")
                    .font(.ft8afUI(size: 14, weight: .semibold))
                    .foregroundStyle(signal)
                    .frame(width: 34, height: 34)
                    .background(
                        RoundedRectangle(cornerRadius: 8)
                            .fill(signal.opacity(0.1))
                    )
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(bgApp)
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(borderSubtle)
                .frame(height: 1)
        }
    }

    private func historyDateText(_ activation: PotaActivationRecord) -> String {
        let start = Date(timeIntervalSince1970: Double(activation.startedAtMs) / 1000.0)
        return start.formatted(date: .abbreviated, time: .shortened)
    }
}

// MARK: - Spot row

private struct PotaSpotRow: View {
    let spot: PotaSpot
    let now: Date
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(alignment: .top, spacing: 0) {
                // Activator + park
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(spot.activator)
                            .font(.ft8afMono(size: 13, weight: .bold))
                            .foregroundStyle(textPrimary)
                        Text(spot.reference)
                            .font(.ft8afMono(size: 11, weight: .semibold))
                            .foregroundStyle(statusConfirmed)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .background(
                                RoundedRectangle(cornerRadius: 3)
                                    .fill(statusConfirmed.opacity(0.14))
                            )
                        if !spot.isFT8 {
                            Text(spot.mode)
                                .font(.ft8afMono(size: 10, weight: .semibold))
                                .foregroundStyle(textFaint)
                                .padding(.horizontal, 4)
                                .padding(.vertical, 1)
                                .background(
                                    RoundedRectangle(cornerRadius: 3)
                                        .fill(bgSurface3)
                                )
                        }
                    }
                    if !parkLine.isEmpty {
                        Text(parkLine)
                            .font(.ft8afUI(size: 11))
                            .foregroundStyle(textMuted)
                            .lineLimit(1)
                    }
                    if !spot.comments.isEmpty {
                        Text("\u{201C}\(spot.comments)\u{201D}")
                            .font(.ft8afUI(size: 11))
                            .foregroundStyle(textFaint)
                            .lineLimit(1)
                    }
                }

                Spacer(minLength: 8)

                // Frequency + age + spotter
                VStack(alignment: .trailing, spacing: 2) {
                    Text(spot.frequencyText)
                        .font(.ft8afMono(size: 12, weight: .medium))
                        .foregroundStyle(accent)
                    Text(ageText)
                        .font(.ft8afMono(size: 10, weight: .medium))
                        .foregroundStyle(textFaint)
                    if !spot.spotter.isEmpty {
                        Text("de \(spot.spotter)")
                            .font(.ft8afMono(size: 9))
                            .foregroundStyle(textFaint)
                            .lineLimit(1)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .background(bgApp)
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(borderSubtle)
                .frame(height: 1)
        }
    }

    private var parkLine: String {
        var line = spot.parkName
        if !spot.locationDesc.isEmpty {
            line += line.isEmpty ? spot.locationDesc : " · \(spot.locationDesc)"
        }
        return line
    }

    private var ageText: String {
        guard let secs = PotaSpots.ageSeconds(spotTimeUtc: spot.spotTimeUtc, now: now) else {
            return ""
        }
        return relativeAge(secondsAgo: secs)
    }
}

// MARK: - Share sheet

private struct PotaShareSheet: UIViewControllerRepresentable {
    let items: [URL]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
