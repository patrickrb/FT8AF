import FT8Engine
import SwiftUI

/// Canvas-drawn azimuthal equidistant projection centered on the operator's grid.
/// Shows range rings, compass bearings, and station markers projected radially
/// from the operator's position.
struct AzimuthalMapCanvas: View {
    let markers: [MapMarker]
    @Binding var selectedMarker: MapMarker?
    var operatorGrid: String = ""

    /// Range ring distances in km.
    private let ringDistances: [Double] = [2500, 5000, 10000, 15000, 20000]
    /// Maximum distance in km (half the Earth's circumference).
    private let maxDistKm: Double = 20015.0
    /// Compass labels at 45-degree intervals.
    private let compassLabels = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"]

    var body: some View {
        GeometryReader { geo in
            let size = geo.size
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let radius = min(size.width, size.height) / 2 - 20

            Canvas { context, _ in
                // Background
                context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(bgSurface))

                // Outer circle (Earth boundary)
                let outerRect = CGRect(
                    x: center.x - radius,
                    y: center.y - radius,
                    width: radius * 2,
                    height: radius * 2
                )
                context.fill(Path(ellipseIn: outerRect), with: .color(bgSurface2))
                context.stroke(Path(ellipseIn: outerRect),
                               with: .color(textDim.opacity(0.4)), lineWidth: 1)

                // Range rings
                for dist in ringDistances {
                    let ringRadius = radius * CGFloat(dist / maxDistKm)
                    let ringRect = CGRect(
                        x: center.x - ringRadius,
                        y: center.y - ringRadius,
                        width: ringRadius * 2,
                        height: ringRadius * 2
                    )
                    context.stroke(Path(ellipseIn: ringRect),
                                   with: .color(textDim.opacity(0.25)),
                                   style: StrokeStyle(lineWidth: 0.5, dash: [3, 3]))

                    // Distance label on right side of ring
                    let labelText = dist >= 1000 ? "\(Int(dist / 1000))k" : "\(Int(dist))"
                    let text = context.resolve(Text(labelText)
                        .font(.system(size: 8, weight: .medium, design: .monospaced))
                        .foregroundStyle(textFaint))
                    let labelX = center.x + ringRadius + 2
                    let labelY = center.y - 5
                    if labelX < size.width - 20 {
                        context.draw(text, at: CGPoint(x: labelX, y: labelY), anchor: .leading)
                    }
                }

                // Compass bearing lines (8 directions at 45-degree intervals)
                for i in 0..<8 {
                    let angle = Double(i) * 45.0
                    let rad = angle * .pi / 180
                    let endX = center.x + radius * CGFloat(sin(rad))
                    let endY = center.y - radius * CGFloat(cos(rad))

                    let linePath = Path { p in
                        p.move(to: center)
                        p.addLine(to: CGPoint(x: endX, y: endY))
                    }
                    context.stroke(linePath,
                                   with: .color(textDim.opacity(i == 0 ? 0.4 : 0.15)),
                                   lineWidth: i == 0 ? 1 : 0.5)

                    // Compass label beyond the ring
                    let labelDist = radius + 12
                    let lx = center.x + labelDist * CGFloat(sin(rad))
                    let ly = center.y - labelDist * CGFloat(cos(rad))
                    let compassText = context.resolve(Text(compassLabels[i])
                        .font(.system(size: 9, weight: .semibold, design: .monospaced))
                        .foregroundStyle(i == 0 ? textMuted : textFaint))
                    context.draw(compassText, at: CGPoint(x: lx, y: ly), anchor: .center)
                }

                // Simplified continent outlines projected
                if let opPos = operatorPosition {
                    drawProjectedContinents(
                        context: context, center: center, radius: radius, opPos: opPos
                    )
                }

                // Draw great circle line to selected marker
                if let marker = selectedMarker, let opPos = operatorPosition {
                    let brg = bearing(from: opPos, to: (marker.lat, marker.lon))
                    let dist = haversineDistance(from: opPos, to: (marker.lat, marker.lon))
                    let r = radius * CGFloat(min(dist / maxDistKm, 1.0))
                    let rad = brg * .pi / 180
                    let endPt = CGPoint(
                        x: center.x + r * CGFloat(sin(rad)),
                        y: center.y - r * CGFloat(cos(rad))
                    )
                    let gcPath = Path { p in
                        p.move(to: center)
                        p.addLine(to: endPt)
                    }
                    context.stroke(gcPath, with: .color(accent.opacity(0.5)),
                                   style: StrokeStyle(lineWidth: 1.5, dash: [4, 4]))
                }

                // Draw station markers
                if let opPos = operatorPosition {
                    for marker in markers {
                        let brg = bearing(from: opPos, to: (marker.lat, marker.lon))
                        let dist = haversineDistance(from: opPos, to: (marker.lat, marker.lon))
                        guard dist <= maxDistKm else { continue }

                        let r = radius * CGFloat(dist / maxDistKm)
                        let rad = brg * .pi / 180
                        let mx = center.x + r * CGFloat(sin(rad))
                        let my = center.y - r * CGFloat(cos(rad))

                        let color = markerColor(marker.status)
                        let isSelected = selectedMarker?.id == marker.id

                        // Outer glow
                        let glowSize: CGFloat = isSelected ? 16 : 12
                        let glowRect = CGRect(
                            x: mx - CGFloat(glowSize / 2),
                            y: my - CGFloat(glowSize / 2),
                            width: CGFloat(glowSize),
                            height: CGFloat(glowSize)
                        )
                        context.fill(Path(ellipseIn: glowRect), with: .color(color.opacity(0.25)))

                        // Inner dot
                        let dotSize: CGFloat = isSelected ? 8 : 6
                        let dotRect = CGRect(
                            x: mx - CGFloat(dotSize / 2),
                            y: my - CGFloat(dotSize / 2),
                            width: CGFloat(dotSize),
                            height: CGFloat(dotSize)
                        )
                        context.fill(Path(ellipseIn: dotRect), with: .color(color))
                    }
                }

                // Operator position at center (diamond)
                if operatorPosition != nil {
                    let dSize: CGFloat = 8
                    let diamond = Path { p in
                        p.move(to: CGPoint(x: center.x, y: center.y - dSize))
                        p.addLine(to: CGPoint(x: center.x + dSize, y: center.y))
                        p.addLine(to: CGPoint(x: center.x, y: center.y + dSize))
                        p.addLine(to: CGPoint(x: center.x - dSize, y: center.y))
                        p.closeSubpath()
                    }
                    context.fill(diamond, with: .color(accent))
                    context.stroke(diamond, with: .color(accentGlow), lineWidth: 1)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .contentShape(Rectangle())
            .onTapGesture { location in
                guard let opPos = operatorPosition else { return }
                let cx = size.width / 2
                let cy = size.height / 2
                let r = min(size.width, size.height) / 2 - 20

                selectedMarker = markers.first { marker in
                    let brg = bearing(from: opPos, to: (marker.lat, marker.lon))
                    let dist = haversineDistance(from: opPos, to: (marker.lat, marker.lon))
                    guard dist <= maxDistKm else { return false }

                    let mr = r * CGFloat(dist / maxDistKm)
                    let rad = brg * .pi / 180
                    let mx = cx + mr * CGFloat(sin(rad))
                    let my = cy - mr * CGFloat(cos(rad))

                    let dx = abs(location.x - mx)
                    let dy = abs(location.y - my)
                    return dx < 15 && dy < 15
                }
            }
        }
    }

    private var operatorPosition: (Double, Double)? {
        operatorGrid.isEmpty ? nil : gridToLatLon(operatorGrid)
    }

    private func markerColor(_ status: MarkerStatus) -> Color {
        switch status {
        case .cq:       return accent
        case .worked:   return signal
        case .forMe:    return target
        case .standard: return textMuted
        }
    }

    /// Draw simplified continent outlines projected onto the azimuthal map.
    private func drawProjectedContinents(
        context: GraphicsContext, center: CGPoint, radius: CGFloat,
        opPos: (Double, Double)
    ) {
        // Simplified continent polygons as (lon, lat) pairs
        let continents: [[(Double, Double)]] = [
            // North America
            [(-170,72),(-170,55),(-130,50),(-120,35),(-100,25),(-80,25),(-75,30),(-65,45),(-55,50),(-80,60),(-100,65),(-130,70),(-170,72)],
            // South America
            [(-80,12),(-80,-5),(-75,-15),(-70,-25),(-65,-35),(-70,-55),(-75,-55),(-60,-35),(-50,-25),(-35,-5),(-50,5),(-60,10),(-80,12)],
            // Europe
            [(-10,36),(0,38),(5,44),(10,48),(20,55),(30,60),(40,65),(30,70),(20,70),(10,65),(0,55),(-5,48),(-10,36)],
            // Africa
            [(-15,35),(-5,30),(10,35),(35,30),(50,12),(40,0),(30,-15),(25,-35),(20,-35),(15,-25),(10,-5),(0,5),(-10,10),(-15,35)],
            // Asia / Oceania
            [(40,30),(50,40),(60,55),(80,60),(100,70),(130,65),(145,60),(150,55),(140,45),(130,35),(120,25),(100,25),(80,30),(60,35),(40,30)],
            // Australia
            [(115,-15),(130,-15),(145,-20),(150,-30),(145,-38),(130,-35),(115,-35),(115,-25),(115,-15)],
        ]

        for continent in continents {
            let path = Path { p in
                var first = true
                for (lon, lat) in continent {
                    let brg = bearing(from: opPos, to: (lat, lon))
                    let dist = haversineDistance(from: opPos, to: (lat, lon))
                    guard dist <= maxDistKm else {
                        first = true
                        continue
                    }

                    let r = radius * CGFloat(dist / maxDistKm)
                    let rad = brg * .pi / 180
                    let x = center.x + r * CGFloat(sin(rad))
                    let y = center.y - r * CGFloat(cos(rad))
                    let pt = CGPoint(x: x, y: y)

                    if first {
                        p.move(to: pt)
                        first = false
                    } else {
                        p.addLine(to: pt)
                    }
                }
                p.closeSubpath()
            }
            context.fill(path, with: .color(bgElev))
            context.stroke(path, with: .color(textDim.opacity(0.5)), lineWidth: 0.5)
        }
    }
}
