import FT8Engine
import SwiftUI

/// Canvas-drawn equirectangular world map with station markers.
struct WorldMapCanvas: View {
    let markers: [MapMarker]
    @Binding var selectedMarker: MapMarker?
    var operatorGrid: String = ""

    var body: some View {
        Canvas { context, size in
            let w = size.width
            let h = size.height

            // Background ocean
            context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(bgSurface))

            // Draw simplified continent outlines
            drawContinents(context: context, width: w, height: h)

            // Draw grid lines
            drawGridLines(context: context, width: w, height: h)

            // Operator position
            let opPos: (Double, Double)? = operatorGrid.isEmpty ? nil : gridToLatLon(operatorGrid)

            // Draw great circle line to selected marker
            if let marker = selectedMarker, let op = opPos {
                drawGreatCircle(
                    context: context, width: w, height: h,
                    from: op, to: (marker.lat, marker.lon)
                )
            }

            // Draw station markers with status-based colors
            for marker in markers {
                let x = (marker.lon + 180) / 360 * Double(w)
                let y = (90 - marker.lat) / 180 * Double(h)

                let color = markerColor(marker.status)
                let isSelected = selectedMarker?.id == marker.id

                // Outer glow
                let glowSize: CGFloat = isSelected ? 16 : 12
                let glowRect = CGRect(x: x - Double(glowSize/2), y: y - Double(glowSize/2),
                                      width: Double(glowSize), height: Double(glowSize))
                context.fill(Path(ellipseIn: glowRect), with: .color(color.opacity(0.25)))

                // Inner dot
                let dotSize: CGFloat = isSelected ? 8 : 6
                let markerRect = CGRect(x: x - Double(dotSize/2), y: y - Double(dotSize/2),
                                        width: Double(dotSize), height: Double(dotSize))
                context.fill(Path(ellipseIn: markerRect), with: .color(color))
            }

            // Draw operator position marker
            if let op = opPos {
                let ox = (op.1 + 180) / 360 * Double(w)
                let oy = (90 - op.0) / 180 * Double(h)

                // Diamond shape for operator
                let size: CGFloat = 8
                let diamond = Path { p in
                    p.move(to: CGPoint(x: ox, y: oy - Double(size)))
                    p.addLine(to: CGPoint(x: ox + Double(size), y: oy))
                    p.addLine(to: CGPoint(x: ox, y: oy + Double(size)))
                    p.addLine(to: CGPoint(x: ox - Double(size), y: oy))
                    p.closeSubpath()
                }
                context.fill(diamond, with: .color(accent))
                context.stroke(diamond, with: .color(accentGlow), lineWidth: 1)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .contentShape(Rectangle())
        .onTapGesture { location in
            // Hit test markers
            selectedMarker = markers.first { marker in
                let x = (marker.lon + 180) / 360
                let y = (90 - marker.lat) / 180
                // We need the canvas size for accurate hit testing; approximate
                let dx = abs(location.x - x * 400) // rough approximation
                let dy = abs(location.y - y * 200)
                return dx < 15 && dy < 15
            }
        }
    }

    private func markerColor(_ status: MarkerStatus) -> Color {
        switch status {
        case .cq:       return accent      // orange for CQ
        case .worked:   return signal      // cyan for worked
        case .forMe:    return target      // pink for messages to me
        case .standard: return textMuted   // dim gray for others
        }
    }

    private func drawGreatCircle(
        context: GraphicsContext, width: CGFloat, height: CGFloat,
        from a: (Double, Double), to b: (Double, Double)
    ) {
        let segments = 60
        let path = Path { p in
            for i in 0...segments {
                let f = Double(i) / Double(segments)
                let (lat, lon) = interpolateGreatCircle(from: a, to: b, fraction: f)
                let x = (lon + 180) / 360 * Double(width)
                let y = (90 - lat) / 180 * Double(height)
                if i == 0 { p.move(to: CGPoint(x: x, y: y)) }
                else { p.addLine(to: CGPoint(x: x, y: y)) }
            }
        }
        context.stroke(path, with: .color(accent.opacity(0.5)),
                       style: StrokeStyle(lineWidth: 1.5, dash: [4, 4]))
    }

    /// Spherical linear interpolation between two lat/lon points.
    private func interpolateGreatCircle(
        from a: (Double, Double), to b: (Double, Double), fraction f: Double
    ) -> (Double, Double) {
        let lat1 = a.0 * .pi / 180, lon1 = a.1 * .pi / 180
        let lat2 = b.0 * .pi / 180, lon2 = b.1 * .pi / 180

        let dLon = lon2 - lon1
        let cosLat1 = cos(lat1), sinLat1 = sin(lat1)
        let cosLat2 = cos(lat2), sinLat2 = sin(lat2)

        let d = acos(sinLat1 * sinLat2 + cosLat1 * cosLat2 * cos(dLon))
        guard d > 1e-10 else { return a } // same point

        let sinD = sin(d)
        let A = sin((1 - f) * d) / sinD
        let B = sin(f * d) / sinD

        let x = A * cosLat1 * cos(lon1) + B * cosLat2 * cos(lon2)
        let y = A * cosLat1 * sin(lon1) + B * cosLat2 * sin(lon2)
        let z = A * sinLat1 + B * sinLat2

        let lat = atan2(z, sqrt(x * x + y * y)) * 180 / .pi
        let lon = atan2(y, x) * 180 / .pi
        return (lat, lon)
    }

    private func drawGridLines(context: GraphicsContext, width: CGFloat, height: CGFloat) {
        for lat in stride(from: -60.0, through: 60.0, by: 30.0) {
            let y = (90 - lat) / 180 * Double(height)
            let path = Path { p in
                p.move(to: CGPoint(x: 0, y: y))
                p.addLine(to: CGPoint(x: Double(width), y: y))
            }
            context.stroke(path, with: .color(textDim.opacity(0.3)), lineWidth: 0.5)
        }
        for lon in stride(from: -150.0, through: 180.0, by: 30.0) {
            let x = (lon + 180) / 360 * Double(width)
            let path = Path { p in
                p.move(to: CGPoint(x: x, y: 0))
                p.addLine(to: CGPoint(x: x, y: Double(height)))
            }
            context.stroke(path, with: .color(textDim.opacity(0.3)), lineWidth: 0.5)
        }
    }

    private func drawContinents(context: GraphicsContext, width: CGFloat, height: CGFloat) {
        let continents: [(Color, [(Double, Double)])] = [
            (bgElev, [(-170,72),(-170,55),(-130,50),(-120,35),(-100,25),(-80,25),(-75,30),(-65,45),(-55,50),(-80,60),(-100,65),(-130,70),(-170,72)]),
            (bgElev, [(-80,12),(-80,-5),(-75,-15),(-70,-25),(-65,-35),(-70,-55),(-75,-55),(-60,-35),(-50,-25),(-35,-5),(-50,5),(-60,10),(-80,12)]),
            (bgElev, [(-10,36),(0,38),(5,44),(10,48),(20,55),(30,60),(40,65),(30,70),(20,70),(10,65),(0,55),(-5,48),(-10,36)]),
            (bgElev, [(-15,35),(-5,30),(10,35),(35,30),(50,12),(40,0),(30,-15),(25,-35),(20,-35),(15,-25),(10,-5),(0,5),(-10,10),(-15,35)]),
            (bgElev, [(40,30),(50,40),(60,55),(80,60),(100,70),(130,65),(145,60),(150,55),(140,45),(130,35),(120,25),(100,25),(80,30),(60,35),(40,30)]),
            (bgElev, [(115,-15),(130,-15),(145,-20),(150,-30),(145,-38),(130,-35),(115,-35),(115,-25),(115,-15)]),
        ]

        for (color, points) in continents {
            let path = Path { p in
                for (i, point) in points.enumerated() {
                    let x = (point.0 + 180) / 360 * Double(width)
                    let y = (90 - point.1) / 180 * Double(height)
                    if i == 0 { p.move(to: CGPoint(x: x, y: y)) }
                    else { p.addLine(to: CGPoint(x: x, y: y)) }
                }
                p.closeSubpath()
            }
            context.fill(path, with: .color(color))
            context.stroke(path, with: .color(textDim.opacity(0.5)), lineWidth: 0.5)
        }
    }
}
