import FT8Engine
import SwiftUI

enum ExportFormat: String, CaseIterable, Identifiable {
    case adif = "ADIF"
    case csv = "CSV"
    case cabrillo = "Cabrillo"
    case json = "JSON"

    var id: String { rawValue }

    var fileExtension: String {
        switch self {
        case .adif:     return "adi"
        case .csv:      return "csv"
        case .cabrillo: return "log"
        case .json:     return "json"
        }
    }

    var icon: String {
        switch self {
        case .adif:     return "antenna.radiowaves.left.and.right"
        case .csv:      return "tablecells"
        case .cabrillo: return "trophy"
        case .json:     return "curlybraces"
        }
    }

    var description: String {
        switch self {
        case .adif:     return "Standard amateur radio log exchange format"
        case .csv:      return "Spreadsheet-compatible comma-separated values"
        case .cabrillo: return "Contest log submission format"
        case .json:     return "Machine-readable structured data"
        }
    }
}

struct ExportLogSheet: View {
    let records: [QsoRecord]
    @Environment(\.dismiss) private var dismiss
    @State private var selectedFormat: ExportFormat = .adif
    @State private var dateFilter: DateFilterMode = .all
    @State private var showShareSheet = false
    @State private var exportURL: URL?

    enum DateFilterMode: String, CaseIterable {
        case all = "All QSOs"
        case today = "Today"
        case week = "This Week"
        case month = "This Month"
    }

    var body: some View {
        NavigationStack {
            List {
                // Format selection
                Section {
                    ForEach(ExportFormat.allCases) { format in
                        Button {
                            selectedFormat = format
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: format.icon)
                                    .font(.system(size: 14, weight: .semibold))
                                    .foregroundStyle(selectedFormat == format ? accent : textFaint)
                                    .frame(width: 24)

                                VStack(alignment: .leading, spacing: 2) {
                                    Text(format.rawValue)
                                        .font(.system(size: 14, weight: .semibold))
                                        .foregroundStyle(textPrimary)
                                    Text(format.description)
                                        .font(.system(size: 11))
                                        .foregroundStyle(textFaint)
                                }

                                Spacer()

                                if selectedFormat == format {
                                    Image(systemName: "checkmark.circle.fill")
                                        .foregroundStyle(accent)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                } header: {
                    Text("Format").foregroundStyle(textMuted)
                }
                .listRowBackground(bgSurface)

                // Date range filter
                Section {
                    Picker("Date Range", selection: $dateFilter) {
                        ForEach(DateFilterMode.allCases, id: \.self) { mode in
                            Text(mode.rawValue).tag(mode)
                        }
                    }
                    .foregroundStyle(textPrimary)
                    .tint(accent)
                } header: {
                    Text("Filter").foregroundStyle(textMuted)
                } footer: {
                    Text("\(filteredRecords.count) of \(records.count) QSOs will be exported")
                        .foregroundStyle(textFaint)
                }
                .listRowBackground(bgSurface)

                // Export button
                Section {
                    Button {
                        exportURL = generateExportFile()
                        if exportURL != nil { showShareSheet = true }
                    } label: {
                        HStack {
                            Spacer()
                            Image(systemName: "square.and.arrow.up")
                                .font(.system(size: 14, weight: .semibold))
                            Text("Export \(selectedFormat.rawValue)")
                                .font(.system(size: 15, weight: .bold))
                            Spacer()
                        }
                        .foregroundStyle(bgApp)
                        .padding(.vertical, 4)
                    }
                    .listRowBackground(accent)
                }
            }
            .scrollContentBackground(.hidden)
            .background(bgApp)
            .navigationTitle("Export Log")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .foregroundStyle(accent)
                }
            }
            .sheet(isPresented: $showShareSheet) {
                if let url = exportURL {
                    ShareSheetWrapper(items: [url])
                }
            }
        }
    }

    private var filteredRecords: [QsoRecord] {
        switch dateFilter {
        case .all: return records
        case .today:
            let today = todayString()
            return records.filter { $0.qsoDate == today }
        case .week:
            let cutoff = dateString(daysAgo: 7)
            return records.filter { $0.qsoDate >= cutoff }
        case .month:
            let cutoff = dateString(daysAgo: 30)
            return records.filter { $0.qsoDate >= cutoff }
        }
    }

    private func generateExportFile() -> URL? {
        let recs = filteredRecords
        let content: String
        let fileName = "ft8af_log.\(selectedFormat.fileExtension)"

        switch selectedFormat {
        case .adif:
            content = Adif.export(recs)
        case .csv:
            content = exportCSV(recs)
        case .cabrillo:
            content = exportCabrillo(recs)
        case .json:
            content = exportJSON(recs)
        }

        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)
        try? content.data(using: .utf8)?.write(to: tmp, options: .atomic)
        return tmp
    }

    private func exportCSV(_ records: [QsoRecord]) -> String {
        var out = "Call,Grid,Mode,RST Sent,RST Rcvd,Date,Time,Band,Freq,Comment\n"
        for r in records {
            let fields = [r.call, r.gridsquare, r.mode, r.rstSent, r.rstRcvd,
                          r.qsoDate, r.timeOn, r.band, r.freq, r.comment]
            out += fields.map { csvEscape($0) }.joined(separator: ",") + "\n"
        }
        return out
    }

    private func csvEscape(_ s: String) -> String {
        if s.contains(",") || s.contains("\"") || s.contains("\n") {
            return "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\""
        }
        return s
    }

    private func exportCabrillo(_ records: [QsoRecord]) -> String {
        var out = "START-OF-LOG: 3.0\n"
        out += "CONTEST: FT8-QSO\n"
        out += "CALLSIGN: \(records.first?.stationCallsign ?? "")\n"
        out += "GRID-LOCATOR: \(records.first?.myGridsquare ?? "")\n"
        out += "CATEGORY-MODE: DIGITAL\n"

        for r in records {
            // Cabrillo format: QSO: freq mode date time sent-call sent-rst sent-exch rcvd-call rcvd-rst rcvd-exch
            let freqKhz = (Double(r.freq) ?? 0) * 1000
            let date = formatCabrilloDate(r.qsoDate)
            let time = String(r.timeOn.prefix(4))
            out += "QSO: \(String(format: "%5.0f", freqKhz)) DG \(date) \(time) \(r.stationCallsign) \(r.rstSent) \(r.myGridsquare) \(r.call) \(r.rstRcvd) \(r.gridsquare)\n"
        }
        out += "END-OF-LOG:\n"
        return out
    }

    private func formatCabrilloDate(_ yyyymmdd: String) -> String {
        guard yyyymmdd.count == 8 else { return yyyymmdd }
        let y = yyyymmdd.prefix(4)
        let m = yyyymmdd.dropFirst(4).prefix(2)
        let d = yyyymmdd.suffix(2)
        return "\(y)-\(m)-\(d)"
    }

    private func exportJSON(_ records: [QsoRecord]) -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let data = try? encoder.encode(records) else { return "[]" }
        return String(data: data, encoding: .utf8) ?? "[]"
    }

    private func todayString() -> String {
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyyMMdd"
        fmt.timeZone = TimeZone(identifier: "UTC")
        return fmt.string(from: Date())
    }

    private func dateString(daysAgo: Int) -> String {
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyyMMdd"
        fmt.timeZone = TimeZone(identifier: "UTC")
        let date = Calendar.current.date(byAdding: .day, value: -daysAgo, to: Date()) ?? Date()
        return fmt.string(from: date)
    }
}

private struct ShareSheetWrapper: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
