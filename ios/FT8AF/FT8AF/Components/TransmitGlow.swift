import SwiftUI

/// Full-screen border glow overlay shown during TX. Taps pass through.
struct TransmitGlow: View {
    @State private var pulse = false

    var body: some View {
        RoundedRectangle(cornerRadius: 0)
            .strokeBorder(
                LinearGradient(
                    colors: [
                        Color(red: 1.0, green: 0.3, blue: 0.1).opacity(pulse ? 0.6 : 0.3),
                        Color(red: 1.0, green: 0.5, blue: 0.0).opacity(pulse ? 0.4 : 0.15),
                        Color(red: 1.0, green: 0.3, blue: 0.1).opacity(pulse ? 0.6 : 0.3),
                    ],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                ),
                lineWidth: 4
            )
            .ignoresSafeArea()
            .allowsHitTesting(false)
            .onAppear {
                withAnimation(.easeInOut(duration: 1.2).repeatForever(autoreverses: true)) {
                    pulse = true
                }
            }
    }
}
