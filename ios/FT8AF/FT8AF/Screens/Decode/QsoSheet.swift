import FT8Engine
import SwiftUI

/// Bottom sheet showing station detail when a decode row is tapped.
struct QsoSheet: View {
    let message: DecodeMessage
    var onCall: (DecodeMessage) -> Void = { _ in }
    @Environment(AppState.self) private var appState
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(spacing: 0) {
            // Callsign header
            VStack(spacing: 4) {
                Text(message.callFrom)
                    .font(.system(size: 28, weight: .bold, design: .monospaced))
                    .foregroundStyle(textPrimary)

                HStack(spacing: 8) {
                    if !message.grid.isEmpty {
                        Text(message.grid)
                            .font(.system(size: 14, weight: .medium, design: .monospaced))
                            .foregroundStyle(textMuted)
                    }

                    Button {
                        if let url = URL(string: "https://www.qrz.com/db/\(message.callFrom)") {
                            openURL(url)
                        }
                    } label: {
                        Text("QRZ")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(signal)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(
                                RoundedRectangle(cornerRadius: 4)
                                    .fill(signal.opacity(0.14))
                            )
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.top, 20)
            .padding(.bottom, 16)

            // Stats row
            HStack(spacing: 12) {
                StatBadge(label: "SNR", value: message.snr >= 0 ? "+\(message.snr)" : "\(message.snr)")
                StatBadge(label: "Freq", value: String(format: "%.0f Hz", message.freqHz))
                StatBadge(label: "UTC", value: message.utcTime)

                // Distance & Azimuth (if grids available)
                if let dist = distanceInfo {
                    StatBadge(label: "Dist", value: dist.distance)
                    StatBadge(label: "Azim", value: dist.bearing)
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 16)

            // QSO Sequence Visualizer
            if appState.tx.isActivated {
                VStack(spacing: 6) {
                    Text("QSO PROGRESS")
                        .font(.system(size: 9, weight: .bold, design: .monospaced))
                        .foregroundStyle(textFaint)
                    QsoStageDots(currentStage: appState.tx.stage)
                        .padding(.horizontal, 24)
                }
                .padding(.bottom, 16)
            }

            // QSO path mini-map
            if !appState.settings.myGrid.isEmpty && !message.grid.isEmpty {
                QsoPathMap(myGrid: appState.settings.myGrid, theirGrid: message.grid)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 12)
            }

            // Extra info
            if !message.extra.isEmpty && message.extra != message.grid {
                HStack {
                    Text("Report")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(textFaint)
                    Spacer()
                    Text(message.extra)
                        .font(.system(size: 14, weight: .semibold, design: .monospaced))
                        .foregroundStyle(textPrimary)
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 16)
            }

            // TX message banner
            if appState.tx.isActivated && !appState.tx.txMessage.isEmpty {
                HStack(spacing: 8) {
                    Text(appState.tx.isTransmitting ? "TX NOW" : "TX NEXT")
                        .font(.system(size: 9, weight: .bold, design: .monospaced))
                        .foregroundStyle(appState.tx.isTransmitting ? bgApp : accent)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(
                            RoundedRectangle(cornerRadius: 3)
                                .fill(appState.tx.isTransmitting ? accent : accentSoft)
                        )

                    Text(appState.tx.txMessage)
                        .font(.system(size: 12, weight: .medium, design: .monospaced))
                        .foregroundStyle(textPrimary)
                        .lineLimit(1)

                    Spacer()
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 8)
                .background(
                    RoundedRectangle(cornerRadius: 8)
                        .fill(appState.tx.isTransmitting ? accent.opacity(0.08) : bgSurface3.opacity(0.5))
                )
                .padding(.horizontal, 16)
                .padding(.bottom, 12)
            }

            Spacer()

            // Call button
            Button {
                onCall(message)
                dismiss()
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "antenna.radiowaves.left.and.right")
                        .font(.system(size: 14, weight: .semibold))
                    Text("Call \(message.callFrom)")
                        .font(.system(size: 16, weight: .bold, design: .monospaced))
                }
                .foregroundStyle(bgApp)
                .frame(maxWidth: .infinity)
                .frame(height: 50)
                .background(
                    RoundedRectangle(cornerRadius: 12)
                        .fill(accent)
                )
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 24)
            .padding(.bottom, 30)
        }
        .frame(maxWidth: .infinity)
        .background(bgSurface2)
    }

    private var distanceInfo: (distance: String, bearing: String)? {
        let myGrid = appState.settings.myGrid
        guard !myGrid.isEmpty, !message.grid.isEmpty,
              let myPos = gridToLatLon(myGrid),
              let theirPos = gridToLatLon(message.grid) else { return nil }

        let dist = haversineDistance(from: myPos, to: theirPos)
        let brg = bearing(from: myPos, to: theirPos)
        return (formatDistance(dist), formatBearing(brg))
    }
}

private struct StatBadge: View {
    let label: String
    let value: String

    var body: some View {
        VStack(spacing: 4) {
            Text(label)
                .font(.system(size: 10, weight: .medium))
                .foregroundStyle(textFaint)
                .textCase(.uppercase)
            Text(value)
                .font(.system(size: 13, weight: .semibold, design: .monospaced))
                .foregroundStyle(textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(minWidth: 48)
        .padding(.horizontal, 8)
        .padding(.vertical, 8)
        .background(
            RoundedRectangle(cornerRadius: 8)
                .fill(bgSurface3)
        )
    }
}
