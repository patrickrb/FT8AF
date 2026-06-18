import SwiftUI

/// Confetti burst animation triggered on QSO completion.
struct QsoCelebration: View {
    let onFinished: () -> Void

    @State private var particles: [Particle] = []
    @State private var opacity: Double = 1.0

    private let colors: [Color] = [accent, signal, target, statusConfirmed, statusNew, statusWarn]

    var body: some View {
        GeometryReader { geo in
            ZStack {
                ForEach(particles) { p in
                    Circle()
                        .fill(p.color)
                        .frame(width: p.size, height: p.size)
                        .position(p.position)
                        .opacity(opacity)
                }
            }
            .onAppear {
                spawnParticles(in: geo.size)
                // Fade out after 1.5 seconds
                withAnimation(.easeOut(duration: 0.5).delay(1.5)) {
                    opacity = 0
                }
                // Remove after animation completes
                DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                    onFinished()
                }
            }
        }
        .allowsHitTesting(false)
        .ignoresSafeArea()
    }

    private func spawnParticles(in size: CGSize) {
        let centerX = size.width / 2
        let centerY = size.height * 0.4

        particles = (0..<40).map { _ in
            let angle = Double.random(in: 0..<2 * .pi)
            let distance = Double.random(in: 60...200)
            let targetX = centerX + cos(angle) * distance
            let targetY = centerY + sin(angle) * distance - Double.random(in: 20...80)

            return Particle(
                color: colors.randomElement()!,
                size: CGFloat.random(in: 4...10),
                position: CGPoint(x: centerX, y: centerY),
                targetPosition: CGPoint(x: targetX, y: targetY)
            )
        }

        // Animate particles outward
        withAnimation(.easeOut(duration: 1.0)) {
            for i in particles.indices {
                particles[i].position = particles[i].targetPosition
            }
        }
    }
}

private struct Particle: Identifiable {
    let id = UUID()
    let color: Color
    let size: CGFloat
    var position: CGPoint
    let targetPosition: CGPoint
}
