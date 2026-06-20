import SwiftUI

enum PotaTab: String, CaseIterable, Identifiable {
    case activate = "Activate"
    case hunt = "Hunt"
    case history = "History"

    var id: String { rawValue }
}

struct PotaScreen: View {
    @Environment(AppState.self) private var appState
    @State private var selectedTab: PotaTab = .hunt

    var body: some View {
        VStack(spacing: 0) {
            // Top bar
            HStack {
                Text("POTA")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(textPrimary)

                Spacer()

                if appState.pota.isActivating {
                    HStack(spacing: 4) {
                        Circle()
                            .fill(statusConfirmed)
                            .frame(width: 6, height: 6)
                        Text("ACTIVE")
                            .font(.system(size: 10, weight: .bold, design: .monospaced))
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
    }

    // MARK: - Activate Tab

    @ViewBuilder
    private var activateTab: some View {
        let pota = appState.pota

        if pota.isActivating {
            activeSessionView(pota: pota)
        } else {
            startActivationView
        }
    }

    private var startActivationView: some View {
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

            // Park reference input
            VStack(spacing: 8) {
                HStack {
                    Text("Park Ref:")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(textMuted)
                    TextField("K-0000", text: Bindable(appState.pota).parkInput)
                        .font(.system(size: 14, weight: .medium, design: .monospaced))
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

                // Notes field
                HStack {
                    Text("Notes:")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(textMuted)
                    TextField("Optional notes", text: Bindable(appState.pota).notes)
                        .font(.system(size: 13))
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
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(bgApp)
                    .frame(width: 200, height: 44)
                    .background(
                        RoundedRectangle(cornerRadius: 10)
                            .fill(appState.pota.parkInput.isEmpty ? textFaint : statusConfirmed)
                    )
            }
            .buttonStyle(.plain)
            .disabled(appState.pota.parkInput.isEmpty)

            Spacer()
        }
    }

    private func activeSessionView(pota: PotaState) -> some View {
        VStack(spacing: 16) {
            // Activation card
            VStack(spacing: 12) {
                HStack {
                    Image(systemName: "tree.fill")
                        .font(.system(size: 16))
                        .foregroundStyle(statusConfirmed)
                    Text(pota.parkInput)
                        .font(.system(size: 18, weight: .bold, design: .monospaced))
                        .foregroundStyle(statusConfirmed)
                    Spacer()
                }

                if !pota.notes.isEmpty {
                    Text(pota.notes)
                        .font(.system(size: 12))
                        .foregroundStyle(textMuted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                Divider().overlay(borderSubtle)

                // Stats row
                HStack(spacing: 0) {
                    activationStat(
                        label: "QSOs",
                        value: "\(pota.activationQsoCount)",
                        color: pota.activationQsoCount >= 10 ? statusConfirmed : accent
                    )
                    activationStat(
                        label: "Needed",
                        value: "\(max(0, 10 - pota.activationQsoCount))",
                        color: pota.activationQsoCount >= 10 ? statusConfirmed : statusWarn
                    )
                    activationStat(
                        label: "Elapsed",
                        value: elapsedString(from: pota.activationStartTime),
                        color: textMuted
                    )
                }

                // Progress bar to 10 QSOs
                VStack(spacing: 4) {
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            RoundedRectangle(cornerRadius: 3)
                                .fill(bgSurface3)
                            RoundedRectangle(cornerRadius: 3)
                                .fill(pota.activationQsoCount >= 10 ? statusConfirmed : accent)
                                .frame(width: geo.size.width * min(CGFloat(pota.activationQsoCount) / 10.0, 1.0))
                        }
                    }
                    .frame(height: 6)

                    Text(pota.activationQsoCount >= 10 ? "Activation complete!" : "\(pota.activationQsoCount)/10 for valid activation")
                        .font(.system(size: 10, weight: .medium, design: .monospaced))
                        .foregroundStyle(pota.activationQsoCount >= 10 ? statusConfirmed : textFaint)
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

            // End activation button
            Button {
                endActivation()
            } label: {
                Text("End Activation")
                    .font(.system(size: 14, weight: .bold))
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
                .font(.system(size: 22, weight: .bold, design: .monospaced))
                .foregroundStyle(color)
            Text(label)
                .font(.system(size: 10, weight: .medium, design: .monospaced))
                .foregroundStyle(textFaint)
        }
        .frame(maxWidth: .infinity)
    }

    private func startActivation() {
        appState.pota.isActivating = true
        appState.pota.activationQsoCount = 0
        appState.pota.activationStartTime = Date()
        if !appState.pota.parkRefs.contains(appState.pota.parkInput) {
            appState.pota.parkRefs.append(appState.pota.parkInput)
        }
    }

    private func endActivation() {
        appState.pota.isActivating = false
        appState.pota.activationStartTime = nil
    }

    private func elapsedString(from date: Date?) -> String {
        guard let date else { return "0:00" }
        let elapsed = Int(Date().timeIntervalSince(date))
        let hrs = elapsed / 3600
        let mins = (elapsed % 3600) / 60
        if hrs > 0 {
            return "\(hrs):\(String(format: "%02d", mins))"
        }
        return "\(mins)m"
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
        Group {
            if appState.pota.parkRefs.isEmpty {
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
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(appState.pota.parkRefs, id: \.self) { ref in
                            HStack {
                                Image(systemName: "tree.fill")
                                    .font(.system(size: 12))
                                    .foregroundStyle(statusConfirmed.opacity(0.6))

                                Text(ref)
                                    .font(.system(size: 14, weight: .bold, design: .monospaced))
                                    .foregroundStyle(textPrimary)

                                Spacer()

                                Image(systemName: "chevron.right")
                                    .font(.system(size: 10, weight: .semibold))
                                    .foregroundStyle(textFaint)
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 12)
                            .background(bgApp)
                            .overlay(alignment: .bottom) {
                                Rectangle()
                                    .fill(borderSubtle)
                                    .frame(height: 1)
                            }
                        }
                    }
                    .padding(.bottom, 100)
                }
            }
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
