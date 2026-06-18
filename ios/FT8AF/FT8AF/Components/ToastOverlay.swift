import SwiftUI

/// Lightweight toast notification overlay. Shows a brief message that auto-dismisses.
struct ToastOverlay: View {
    let message: String
    let icon: String?
    let onDismiss: () -> Void

    @State private var opacity: Double = 0

    var body: some View {
        VStack {
            HStack(spacing: 8) {
                if let icon {
                    Image(systemName: icon)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(accent)
                }
                Text(message)
                    .font(.system(size: 13, weight: .semibold, design: .monospaced))
                    .foregroundStyle(textPrimary)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(
                RoundedRectangle(cornerRadius: 10)
                    .fill(bgSurface2)
                    .overlay(
                        RoundedRectangle(cornerRadius: 10)
                            .strokeBorder(borderStrong, lineWidth: 1)
                    )
                    .shadow(color: .black.opacity(0.3), radius: 8, y: 4)
            )

            Spacer()
        }
        .padding(.top, 60)
        .opacity(opacity)
        .allowsHitTesting(false)
        .onAppear {
            withAnimation(.easeOut(duration: 0.2)) { opacity = 1 }
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                withAnimation(.easeIn(duration: 0.3)) { opacity = 0 }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                    onDismiss()
                }
            }
        }
    }
}

/// Toast state managed at the app level.
@Observable @MainActor
final class ToastState {
    var currentMessage: String?
    var currentIcon: String?

    func show(_ message: String, icon: String? = nil) {
        currentMessage = message
        currentIcon = icon
    }

    func dismiss() {
        currentMessage = nil
        currentIcon = nil
    }
}
