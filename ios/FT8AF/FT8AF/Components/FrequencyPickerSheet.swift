import SwiftUI
import UIKit

/// Bottom sheet listing all HF amateur bands with their FT8 dial frequencies.
struct FrequencyPickerSheet: View {
    @Environment(AppState.self) private var appState
    @Environment(\.dismiss) private var dismiss

    private let bands: [(band: String, freq: String)] = [
        ("160M", "1.840 MHz"),
        ("80M", "3.573 MHz"),
        ("60M", "5.357 MHz"),
        ("40M", "7.074 MHz"),
        ("30M", "10.136 MHz"),
        ("20M", "14.074 MHz"),
        ("17M", "18.100 MHz"),
        ("15M", "21.074 MHz"),
        ("12M", "24.915 MHz"),
        ("10M", "28.074 MHz"),
        ("6M", "50.313 MHz"),
    ]

    var body: some View {
        NavigationStack {
            List {
                ForEach(bands, id: \.band) { item in
                    Button {
                        UISelectionFeedbackGenerator().selectionChanged()
                        appState.settings.band = item.band
                        SettingsPersistence.save(appState.settings)
                        dismiss()
                    } label: {
                        HStack {
                            Circle()
                                .fill(bandColor(for: item.band))
                                .frame(width: 8, height: 8)

                            Text(item.band)
                                .font(.ft8afMono(size: 16, weight: .bold))
                                .foregroundStyle(textPrimary)

                            Text("—")
                                .foregroundStyle(textDim)

                            Text(item.freq)
                                .font(.ft8afMono(size: 14, weight: .medium))
                                .foregroundStyle(textMuted)

                            Spacer()

                            if appState.settings.band == item.band {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundStyle(accent)
                            }
                        }
                    }
                    .listRowBackground(bgSurface)
                }
            }
            .scrollContentBackground(.hidden)
            .background(bgApp)
            .navigationTitle("Select Band")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                        .foregroundStyle(accent)
                }
            }
        }
    }
}
