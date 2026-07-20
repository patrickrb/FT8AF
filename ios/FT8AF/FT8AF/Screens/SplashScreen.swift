import SwiftUI

/// Animated splash screen shown on app launch.
struct SplashScreen: View {
    @State private var logoScale: CGFloat = 0.6
    @State private var logoOpacity: Double = 0
    @State private var titleOpacity: Double = 0
    @State private var subtitleOpacity: Double = 0
    @State private var progressValue: Double = 0

    let onFinished: () -> Void

    var body: some View {
        ZStack {
            bgApp.ignoresSafeArea()

            VStack(spacing: 24) {
                Spacer()

                // App icon / compass backdrop
                ZStack {
                    // Outer ring
                    Circle()
                        .strokeBorder(accent.opacity(0.2), lineWidth: 2)
                        .frame(width: 120, height: 120)

                    // Compass rose lines
                    ForEach(0..<8, id: \.self) { i in
                        Rectangle()
                            .fill(textDim.opacity(0.3))
                            .frame(width: 1, height: i % 2 == 0 ? 50 : 30)
                            .offset(y: -(i % 2 == 0 ? 25 : 15))
                            .rotationEffect(.degrees(Double(i) * 45))
                    }

                    // Center icon
                    Image(systemName: "antenna.radiowaves.left.and.right")
                        .font(.ft8afUI(size: 36, weight: .semibold))
                        .foregroundStyle(accent)
                }
                .scaleEffect(logoScale)
                .opacity(logoOpacity)

                // App title
                VStack(spacing: 6) {
                    Text("FT8AF")
                        .font(.ft8afMono(size: 32, weight: .bold))
                        .foregroundStyle(textPrimary)

                    Text("Amateur Radio FT8")
                        .font(.ft8afUI(size: 14, weight: .medium))
                        .foregroundStyle(textMuted)
                }
                .opacity(titleOpacity)

                Spacer()

                // Progress bar
                VStack(spacing: 8) {
                    ProgressView(value: progressValue)
                        .tint(accent)
                        .frame(width: 200)

                    Text("Initializing...")
                        .font(.ft8afMono(size: 11, weight: .medium))
                        .foregroundStyle(textFaint)
                }
                .opacity(subtitleOpacity)

                // Version
                Text(appVersion)
                    .font(.ft8afMono(size: 10, weight: .medium))
                    .foregroundStyle(textDim)
                    .opacity(subtitleOpacity)
                    .padding(.bottom, 40)
            }
        }
        .onAppear {
            withAnimation(.easeOut(duration: 0.6)) {
                logoScale = 1.0
                logoOpacity = 1.0
            }
            withAnimation(.easeOut(duration: 0.5).delay(0.3)) {
                titleOpacity = 1.0
            }
            withAnimation(.easeOut(duration: 0.4).delay(0.5)) {
                subtitleOpacity = 1.0
            }
            withAnimation(.easeInOut(duration: 1.2).delay(0.5)) {
                progressValue = 1.0
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.8) {
                onFinished()
            }
        }
    }

    private var appVersion: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
        return "v\(version) (\(build))"
    }
}
