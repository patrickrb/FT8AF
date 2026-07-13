import FT8Engine
import SwiftUI
import UniformTypeIdentifiers

enum LogbookTab: String, CaseIterable {
    case recent = "Recent"
    case stats = "Stats"
    case awards = "Awards"
}

struct LogbookScreen: View {
    @Environment(AppState.self) private var appState
    @State private var selectedTab: LogbookTab = .recent
    @State private var searchText = ""
    @State private var editingRecord: QsoRecord?
    @State private var showExportSheet = false
    @State private var showImportPicker = false

    var body: some View {
        let logbook = appState.logbook

        VStack(spacing: 0) {
            // Top bar
            HStack {
                Text("Logbook")
                    .font(.ft8afUI(size: 18, weight: .bold))
                    .foregroundStyle(textPrimary)
                Spacer()

                // Catch-up sync to Cloudlog/QRZ (shown when a service is enabled)
                if appState.settings.cloudlogEnabled || appState.settings.qrzLogbookEnabled {
                    let service = OnlineLogService.shared
                    Button {
                        runCatchUpSync()
                    } label: {
                        if service.isSyncing {
                            HStack(spacing: 5) {
                                ProgressView()
                                    .controlSize(.mini)
                                    .tint(accent)
                                Text("\(service.syncDone)/\(service.syncTotal)")
                                    .font(.ft8afMono(size: 11, weight: .medium))
                                    .foregroundStyle(textMuted)
                            }
                        } else {
                            Image(systemName: "icloud.and.arrow.up")
                                .font(.ft8afUI(size: 14, weight: .medium))
                                .foregroundStyle(textMuted)
                        }
                    }
                    .buttonStyle(.plain)
                    .disabled(service.isSyncing)
                    .padding(.trailing, 4)
                }

                // Import button
                Button {
                    showImportPicker = true
                } label: {
                    Image(systemName: "square.and.arrow.down")
                        .font(.ft8afUI(size: 14, weight: .medium))
                        .foregroundStyle(textMuted)
                }
                .buttonStyle(.plain)
                .padding(.trailing, 4)

                // Export button
                Button {
                    showExportSheet = true
                } label: {
                    Image(systemName: "square.and.arrow.up")
                        .font(.ft8afUI(size: 14, weight: .medium))
                        .foregroundStyle(textMuted)
                }
                .buttonStyle(.plain)
                .padding(.trailing, 8)

                Text("\(logbook.totalCount) QSOs")
                    .font(.ft8afMono(size: 12, weight: .medium))
                    .foregroundStyle(textMuted)
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, 8)

            // Sub-tab picker
            Picker("", selection: $selectedTab) {
                ForEach(LogbookTab.allCases, id: \.self) { tab in
                    Text(tab.rawValue).tag(tab)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            .padding(.bottom, 8)

            switch selectedTab {
            case .recent:
                recentTab(logbook: logbook)
            case .stats:
                ScrollView {
                    LogbookChartsView(records: logbook.records)
                        .padding(.bottom, 100)
                }
            case .awards:
                ScrollView {
                    LogbookAwardsView(records: logbook.records)
                        .padding(.bottom, 100)
                }
            }
        }
        .background(bgApp)
        .sheet(item: $editingRecord) { record in
            QsoEditSheet(record: record) { updated in
                QsoLogStore.update(updated, in: appState)
            }
            .presentationDetents([.medium])
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showExportSheet) {
            ExportLogSheet(records: appState.logbook.records)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
        }
        .fileImporter(
            isPresented: $showImportPicker,
            allowedContentTypes: [.plainText, UTType(filenameExtension: "adi") ?? .plainText, UTType(filenameExtension: "adif") ?? .plainText],
            allowsMultipleSelection: false
        ) { result in
            importAdif(result: result)
        }
    }

    @ViewBuilder
    private func recentTab(logbook: LogbookState) -> some View {
        // Search bar
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.ft8afUI(size: 13))
                .foregroundStyle(textFaint)
            TextField("Search callsign, band, date...", text: $searchText)
                .font(.ft8afMono(size: 14))
                .foregroundStyle(textPrimary)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
            if !searchText.isEmpty {
                Button {
                    searchText = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.ft8afUI(size: 13))
                        .foregroundStyle(textFaint)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(
            RoundedRectangle(cornerRadius: 8)
                .fill(bgSurface)
        )
        .padding(.horizontal, 16)
        .padding(.bottom, 8)

        ScrollView {
            VStack(spacing: 12) {
                if searchText.isEmpty {
                    LogbookStats(totalQsos: logbook.totalCount, bandStats: logbook.bandStats)
                        .padding(.horizontal, 16)
                }

                if filteredRecords.isEmpty {
                    if searchText.isEmpty {
                        emptyState
                    } else {
                        noResultsState
                    }
                } else {
                    LazyVStack(spacing: 0) {
                        ForEach(filteredRecords, id: \.id) { record in
                            LogbookRow(
                                record: record,
                                showCloudlogChip: appState.settings.cloudlogEnabled,
                                showQrzChip: appState.settings.qrzLogbookEnabled
                            )
                                .onTapGesture {
                                    editingRecord = record
                                }
                                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                    Button(role: .destructive) {
                                        if let id = record.id {
                                            QsoLogStore.delete(id: id, from: appState)
                                        }
                                    } label: {
                                        Label("Delete", systemImage: "trash")
                                    }
                                }
                        }
                    }
                }
            }
            .padding(.bottom, 100)
        }
    }

    private var filteredRecords: [QsoRecord] {
        guard !searchText.isEmpty else { return appState.logbook.records }
        let query = searchText.uppercased()
        return appState.logbook.records.filter { r in
            r.call.uppercased().contains(query)
            || r.band.uppercased().contains(query)
            || r.gridsquare.uppercased().contains(query)
            || r.qsoDate.contains(query)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "book.closed")
                .font(.ft8afUI(size: 40))
                .foregroundStyle(textFaint)
            Text("No QSOs logged")
                .font(.ft8afUI(size: 16, weight: .medium))
                .foregroundStyle(textMuted)
            Text("Completed contacts will appear here")
                .font(.ft8afUI(size: 13))
                .foregroundStyle(textFaint)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 60)
    }

    /// Upload every unsynced QSO to the enabled services, then toast the result.
    private func runCatchUpSync() {
        Task {
            let s = appState.settings
            let result = await OnlineLogService.shared.syncAll(appState: appState)
            if result.attempted == 0 {
                appState.toast.show("All QSOs already synced", icon: "checkmark.icloud")
                return
            }
            var parts: [String] = []
            if s.cloudlogEnabled { parts.append("CL \(result.cloudlogOk)") }
            if s.qrzLogbookEnabled { parts.append("QRZ \(result.qrzOk)") }
            appState.toast.show(
                "Synced \(parts.joined(separator: ", ")) of \(result.attempted)",
                icon: "icloud.and.arrow.up")
        }
    }

    private func importAdif(result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            guard let url = urls.first else { return }
            guard url.startAccessingSecurityScopedResource() else { return }
            defer { url.stopAccessingSecurityScopedResource() }
            do {
                let data = try Data(contentsOf: url)
                guard let text = String(data: data, encoding: .utf8) else { return }
                let imported = Adif.parse(text)
                guard !imported.isEmpty else {
                    appState.toast.show("No valid QSOs found in file", icon: "exclamationmark.triangle")
                    return
                }
                // Assign IDs and merge (skip duplicates by call+date+time)
                let existing = Set(appState.logbook.records.map { "\($0.call)|\($0.qsoDate)|\($0.timeOn)" })
                let nextId = (appState.logbook.records.map(\.id).compactMap { $0 }.max() ?? 0) + 1
                var added = 0
                for (i, var record) in imported.enumerated() {
                    let key = "\(record.call)|\(record.qsoDate)|\(record.timeOn)"
                    if existing.contains(key) { continue }
                    record.id = nextId + Int64(i)
                    appState.logbook.records.insert(record, at: 0)
                    added += 1
                }
                QsoLogStore.save(appState.logbook.records)
                appState.toast.show("Imported \(added) QSOs", icon: "checkmark.circle")
            } catch {
                appState.toast.show("Import failed", icon: "xmark.circle")
            }
        case .failure:
            appState.toast.show("Could not open file", icon: "xmark.circle")
        }
    }

    private var noResultsState: some View {
        VStack(spacing: 12) {
            Image(systemName: "magnifyingglass")
                .font(.ft8afUI(size: 40))
                .foregroundStyle(textFaint)
            Text("No matches")
                .font(.ft8afUI(size: 16, weight: .medium))
                .foregroundStyle(textMuted)
            Text("No QSOs match \"\(searchText)\"")
                .font(.ft8afUI(size: 13))
                .foregroundStyle(textFaint)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 60)
    }
}

// MARK: - LogbookRow

private struct LogbookRow: View {
    let record: QsoRecord
    var showCloudlogChip = false
    var showQrzChip = false

    var body: some View {
        HStack(spacing: 0) {
            // Band color indicator
            RoundedRectangle(cornerRadius: 2)
                .fill(bandColor(for: record.band))
                .frame(width: 3, height: 36)
                .padding(.trailing, 10)

            // Call + grid + sync chips
            VStack(alignment: .leading, spacing: 2) {
                Text(record.call)
                    .font(.ft8afMono(size: 14, weight: .bold))
                    .foregroundStyle(textPrimary)
                HStack(spacing: 4) {
                    if !record.gridsquare.isEmpty {
                        Text(record.gridsquare)
                            .font(.ft8afMono(size: 10, weight: .medium))
                            .foregroundStyle(textFaint)
                    }
                    if showCloudlogChip {
                        syncChip("CL", synced: record.syncedCloudlog, color: signal)
                    }
                    if showQrzChip {
                        syncChip("QRZ", synced: record.syncedQrz, color: statusConfirmed)
                    }
                }
            }

            Spacer()

            // Band pill + freq
            VStack(spacing: 2) {
                Text(record.band)
                    .font(.ft8afMono(size: 10, weight: .bold))
                    .foregroundStyle(bandColor(for: record.band))
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(
                        RoundedRectangle(cornerRadius: 4)
                            .fill(bandColor(for: record.band).opacity(0.14))
                    )
                if !record.freq.isEmpty {
                    Text(record.freq)
                        .font(.ft8afMono(size: 8, weight: .medium))
                        .foregroundStyle(textDim)
                }
            }
            .padding(.trailing, 10)

            // SNR
            Text(record.rstRcvd)
                .font(.ft8afMono(size: 11, weight: .semibold))
                .foregroundStyle(snrColor)
                .frame(width: 30, alignment: .trailing)
                .padding(.trailing, 10)

            // Date/time
            VStack(alignment: .trailing, spacing: 1) {
                Text(formatDate(record.qsoDate))
                    .font(.ft8afMono(size: 10, weight: .medium))
                    .foregroundStyle(textFaint)
                Text(formatTime(record.timeOn))
                    .font(.ft8afMono(size: 10, weight: .medium))
                    .foregroundStyle(textFaint)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(bgApp)
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(borderSubtle)
                .frame(height: 1)
        }
    }

    /// Tiny "uploaded to <service>" badge — colored once the record is synced.
    private func syncChip(_ label: String, synced: Bool, color: Color) -> some View {
        Text(label)
            .font(.ft8afMono(size: 8, weight: .bold))
            .foregroundStyle(synced ? color : textDim)
            .padding(.horizontal, 4)
            .padding(.vertical, 1)
            .background(
                RoundedRectangle(cornerRadius: 3)
                    .fill(synced ? color.opacity(0.14) : bgSurface2)
            )
    }

    private var snrColor: Color {
        guard let val = Int(record.rstRcvd.trimmingCharacters(in: CharacterSet(charactersIn: "+-").inverted))
              ?? Int(record.rstRcvd) else { return textMuted }
        if val >= 0 { return statusConfirmed }
        if val >= -10 { return signal }
        if val >= -18 { return textMuted }
        return statusBad
    }

    private func formatDate(_ yyyymmdd: String) -> String {
        guard yyyymmdd.count == 8,
              let month = Int(yyyymmdd.dropFirst(4).prefix(2)),
              let day = Int(yyyymmdd.suffix(2)),
              (1...12).contains(month), (1...31).contains(day) else { return "" }
        let months = ["Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"]
        return "\(day) \(months[month - 1])"
    }

    private func formatTime(_ hhmmss: String) -> String {
        guard hhmmss.count >= 4 else { return "--:--" }
        let hh = hhmmss.prefix(2)
        let mm = hhmmss.dropFirst(2).prefix(2)
        return "\(hh):\(mm)"
    }
}

// MARK: - QSO Edit Sheet

private struct QsoEditSheet: View {
    @State var record: QsoRecord
    let onSave: (QsoRecord) -> Void
    @Environment(\.dismiss) private var dismiss

    init(record: QsoRecord, onSave: @escaping (QsoRecord) -> Void) {
        _record = State(initialValue: record)
        self.onSave = onSave
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    editField("Callsign", text: $record.call)
                    editField("Grid", text: $record.gridsquare)
                } header: {
                    Text("Station").foregroundStyle(textMuted)
                }
                .listRowBackground(bgSurface)

                Section {
                    editField("RST Sent", text: $record.rstSent)
                    editField("RST Rcvd", text: $record.rstRcvd)
                } header: {
                    Text("Signal Reports").foregroundStyle(textMuted)
                }
                .listRowBackground(bgSurface)

                Section {
                    HStack {
                        Text("Band")
                            .foregroundStyle(textPrimary)
                        Spacer()
                        Text(record.band)
                            .font(.ft8afMono(size: 17))
                            .foregroundStyle(textMuted)
                    }
                    HStack {
                        Text("Freq")
                            .foregroundStyle(textPrimary)
                        Spacer()
                        Text(record.freq.isEmpty ? "—" : "\(record.freq) MHz")
                            .font(.ft8afMono(size: 17))
                            .foregroundStyle(textMuted)
                    }
                } header: {
                    Text("Frequency").foregroundStyle(textMuted)
                }
                .listRowBackground(bgSurface)

                Section {
                    editField("Comment", text: $record.comment)
                } header: {
                    Text("Notes").foregroundStyle(textMuted)
                }
                .listRowBackground(bgSurface)
            }
            .scrollContentBackground(.hidden)
            .background(bgApp)
            .navigationTitle("Edit QSO")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .foregroundStyle(accent)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        onSave(record)
                        dismiss()
                    }
                    .foregroundStyle(accent)
                    .fontWeight(.bold)
                }
            }
        }
    }

    private func editField(_ label: String, text: Binding<String>) -> some View {
        HStack {
            Text(label)
                .foregroundStyle(textPrimary)
            Spacer()
            TextField("", text: text)
                .font(.ft8afMono(size: 17))
                .multilineTextAlignment(.trailing)
                .foregroundStyle(textPrimary)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
        }
    }
}

// Conformance for sheet(item:)
extension QsoRecord: @retroactive Identifiable {}
