import FT8Engine
import SwiftUI

struct LogbookScreen: View {
    @Environment(AppState.self) private var appState

    var body: some View {
        let logbook = appState.logbook

        VStack(spacing: 0) {
            // Top bar
            HStack {
                Text("Logbook")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(textPrimary)
                Spacer()
                Text("\(logbook.totalCount) QSOs")
                    .font(.system(size: 12, weight: .medium, design: .monospaced))
                    .foregroundStyle(textMuted)
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, 8)

            ScrollView {
                VStack(spacing: 12) {
                    // Stats cards
                    LogbookStats(totalQsos: logbook.totalCount, bandStats: logbook.bandStats)
                        .padding(.horizontal, 16)

                    // QSO records list
                    if logbook.records.isEmpty {
                        emptyState
                    } else {
                        LazyVStack(spacing: 0) {
                            ForEach(logbook.records, id: \.id) { record in
                                LogbookRow(record: record)
                            }
                        }
                    }
                }
                .padding(.bottom, 100)
            }
        }
        .background(bgApp)
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
}

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
