import FT8Engine
import SwiftUI

enum LogbookTab: String, CaseIterable {
    case recent = "Recent"
    case stats = "Stats"
}

struct LogbookScreen: View {
    @Environment(AppState.self) private var appState
    @State private var selectedTab: LogbookTab = .recent
    @State private var searchText = ""
    @State private var editingRecord: QsoRecord?
    @State private var showExportSheet = false

    var body: some View {
        let logbook = appState.logbook

        VStack(spacing: 0) {
            // Top bar
            HStack {
                Text("Logbook")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(textPrimary)
                Spacer()

                // Export button
                Button {
                    showExportSheet = true
                } label: {
                    Image(systemName: "square.and.arrow.up")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(textMuted)
                }
                .buttonStyle(.plain)
                .padding(.trailing, 8)

                Text("\(logbook.totalCount) QSOs")
                    .font(.system(size: 12, weight: .medium, design: .monospaced))
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
            if let url = exportAdifFile() {
                ShareSheet(items: [url])
            }
        }
    }

    @ViewBuilder
    private func recentTab(logbook: LogbookState) -> some View {
        // Search bar
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 13))
                .foregroundStyle(textFaint)
            TextField("Search callsign, band, date...", text: $searchText)
                .font(.system(size: 14, design: .monospaced))
                .foregroundStyle(textPrimary)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
            if !searchText.isEmpty {
                Button {
                    searchText = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 13))
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
                            LogbookRow(record: record)
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

    private func exportAdifFile() -> URL? {
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("ft8af_log.adi")
        let content = Adif.export(appState.logbook.records)
        try? content.data(using: .utf8)?.write(to: tmp, options: .atomic)
        return tmp
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "book.closed")
                .font(.system(size: 40))
                .foregroundStyle(textFaint)
            Text("No QSOs logged")
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(textMuted)
            Text("Completed contacts will appear here")
                .font(.system(size: 13))
                .foregroundStyle(textFaint)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 60)
    }

    private var noResultsState: some View {
        VStack(spacing: 12) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 40))
                .foregroundStyle(textFaint)
            Text("No matches")
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(textMuted)
            Text("No QSOs match \"\(searchText)\"")
                .font(.system(size: 13))
                .foregroundStyle(textFaint)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 60)
    }
}

// MARK: - LogbookRow

private struct LogbookRow: View {
    let record: QsoRecord

    var body: some View {
        HStack(spacing: 0) {
            // Band color indicator
            RoundedRectangle(cornerRadius: 2)
                .fill(bandColor(for: record.band))
                .frame(width: 3, height: 36)
                .padding(.trailing, 10)

            // Call + grid
            VStack(alignment: .leading, spacing: 2) {
                Text(record.call)
                    .font(.system(size: 14, weight: .bold, design: .monospaced))
                    .foregroundStyle(textPrimary)
                if !record.gridsquare.isEmpty {
                    Text(record.gridsquare)
                        .font(.system(size: 10, weight: .medium, design: .monospaced))
                        .foregroundStyle(textFaint)
                }
            }

            Spacer()

            // Band pill
            Text(record.band)
                .font(.system(size: 10, weight: .bold, design: .monospaced))
                .foregroundStyle(bandColor(for: record.band))
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(
                    RoundedRectangle(cornerRadius: 4)
                        .fill(bandColor(for: record.band).opacity(0.14))
                )
                .padding(.trailing, 10)

            // SNR
            Text(record.rstRcvd)
                .font(.system(size: 11, weight: .semibold, design: .monospaced))
                .foregroundStyle(textMuted)
                .frame(width: 30, alignment: .trailing)
                .padding(.trailing, 10)

            // Date/time
            VStack(alignment: .trailing, spacing: 1) {
                Text(formatDate(record.qsoDate))
                    .font(.system(size: 10, weight: .medium, design: .monospaced))
                    .foregroundStyle(textFaint)
                Text(formatTime(record.timeOn))
                    .font(.system(size: 10, weight: .medium, design: .monospaced))
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
                            .font(.system(.body, design: .monospaced))
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
                .font(.system(.body, design: .monospaced))
                .multilineTextAlignment(.trailing)
                .foregroundStyle(textPrimary)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
        }
    }
}

// MARK: - Share Sheet

private struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

// Conformance for sheet(item:)
extension QsoRecord: @retroactive Identifiable {}
