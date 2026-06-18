import SwiftUI

/// Pill-shaped toggle chip for decode filter bar.
struct FilterChip: View {
    let label: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 12, weight: isSelected ? .semibold : .medium, design: .monospaced))
                .foregroundStyle(isSelected ? bgApp : textMuted)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(
                    Capsule()
                        .fill(isSelected ? accent : bgSurface3)
                )
                .overlay(
                    Capsule()
                        .strokeBorder(isSelected ? accent : borderSubtle, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
    }
}
