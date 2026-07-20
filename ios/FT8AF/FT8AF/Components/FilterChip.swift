import SwiftUI

/// Pill-shaped toggle chip for decode filter bar.
/// Selected state uses amber/accent border and fill. Unselected uses subtle border.
struct FilterChip: View {
    let label: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.ft8afMono(size: 12, weight: isSelected ? .semibold : .medium))
                .foregroundStyle(isSelected ? bgApp : textMuted)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(
                    Capsule()
                        .fill(isSelected ? accent : bgSurface3)
                )
                .overlay(
                    Capsule()
                        .strokeBorder(isSelected ? borderAmber : borderSubtle, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
    }
}
