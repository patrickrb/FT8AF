import SwiftUI

enum PotaTab: String, CaseIterable, Identifiable {
    case activate = "Activate"
    case hunt = "Hunt"
    case history = "History"

    var id: String { rawValue }
}

struct PotaScreen: View {
    @State private var selectedTab: PotaTab = .hunt

    var body: some View {
        VStack(spacing: 0) {
            // Top bar
            HStack {
                Text("POTA")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(textPrimary)
                Spacer()
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
    }

    // MARK: - Activate Tab

    private var activateTab: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "tree.fill")
                .font(.system(size: 40))
                .foregroundStyle(statusConfirmed.opacity(0.5))
            Text("Park Activation")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(textPrimary)
            Text("Enter a POTA park reference (e.g. K-1234)\nto start an activation session")
                .font(.system(size: 13))
                .foregroundStyle(textMuted)
                .multilineTextAlignment(.center)

            // Park reference field (stub)
            HStack {
                Text("Park Ref:")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(textMuted)
                RoundedRectangle(cornerRadius: 8)
                    .fill(bgSurface3)
                    .frame(height: 36)
                    .overlay(
                        Text("K-0000")
                            .font(.system(size: 14, design: .monospaced))
                            .foregroundStyle(textFaint),
                        alignment: .leading
                    )
                    .padding(.leading, 8)
            }
            .padding(.horizontal, 40)

            Button { } label: {
                Text("Start Activation")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(bgApp)
                    .frame(width: 200, height: 44)
                    .background(
                        RoundedRectangle(cornerRadius: 10)
                            .fill(statusConfirmed)
                    )
            }
            .buttonStyle(.plain)
            Spacer()
        }
    }

    // MARK: - Hunt Tab

    private var huntTab: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(PotaSpotMock.spots) { spot in
                    PotaSpotRow(spot: spot)
                }
            }
            .padding(.bottom, 100)
        }
    }

    // MARK: - History Tab

    private var historyTab: some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: "clock.arrow.circlepath")
                .font(.system(size: 40))
                .foregroundStyle(textFaint)
            Text("No activations yet")
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(textMuted)
            Text("Past POTA activations will appear here")
                .font(.system(size: 13))
                .foregroundStyle(textFaint)
            Spacer()
        }
    }
}

// MARK: - Mock data

struct PotaSpotMock: Identifiable {
    let id = UUID()
    let activator: String
    let reference: String
    let parkName: String
    let freqKhz: Int
    let mode: String
    let spotTime: String

    static let spots: [PotaSpotMock] = [
        PotaSpotMock(activator: "W1AW", reference: "K-0001", parkName: "Acadia NP", freqKhz: 14074, mode: "FT8", spotTime: "12:28"),
        PotaSpotMock(activator: "KD2OGR", reference: "K-1234", parkName: "Harriman SP", freqKhz: 7074, mode: "FT8", spotTime: "12:25"),
        PotaSpotMock(activator: "N5TIM", reference: "K-0042", parkName: "Big Bend NP", freqKhz: 14074, mode: "FT8", spotTime: "12:22"),
        PotaSpotMock(activator: "VE3XYZ", reference: "VE-0456", parkName: "Algonquin PP", freqKhz: 21074, mode: "FT8", spotTime: "12:18"),
        PotaSpotMock(activator: "DL4RCK", reference: "DL-0123", parkName: "Black Forest NP", freqKhz: 14074, mode: "FT8", spotTime: "12:15"),
    ]
}

private struct PotaSpotRow: View {
    let spot: PotaSpotMock

    var body: some View {
        HStack(spacing: 0) {
            // Activator + park
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(spot.activator)
                        .font(.system(size: 13, weight: .bold, design: .monospaced))
                        .foregroundStyle(textPrimary)
                    Text(spot.reference)
                        .font(.system(size: 11, weight: .semibold, design: .monospaced))
                        .foregroundStyle(statusConfirmed)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 1)
                        .background(
                            RoundedRectangle(cornerRadius: 3)
                                .fill(statusConfirmed.opacity(0.14))
                        )
                }
                Text(spot.parkName)
                    .font(.system(size: 11))
                    .foregroundStyle(textMuted)
                    .lineLimit(1)
            }

            Spacer()

            // Frequency + time
            VStack(alignment: .trailing, spacing: 2) {
                Text("\(spot.freqKhz) kHz")
                    .font(.system(size: 11, weight: .medium, design: .monospaced))
                    .foregroundStyle(textMuted)
                Text(spot.spotTime)
                    .font(.system(size: 10, weight: .medium, design: .monospaced))
                    .foregroundStyle(textFaint)
            }
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
}
