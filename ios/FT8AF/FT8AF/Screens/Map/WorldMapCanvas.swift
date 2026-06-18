import SwiftUI

/// Canvas-drawn equirectangular world map with station markers.
struct WorldMapCanvas: View {
    let markers: [MapMarker]
    @Binding var selectedMarker: MapMarker?

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

            // Draw station markers
            for marker in markers {
                let x = (marker.lon + 180) / 360 * Double(w)
                let y = (90 - marker.lat) / 180 * Double(h)
                let markerRect = CGRect(x: x - 4, y: y - 4, width: 8, height: 8)
                context.fill(Path(ellipseIn: markerRect), with: .color(signal))
                // Outer glow
                let glowRect = CGRect(x: x - 6, y: y - 6, width: 12, height: 12)
                context.fill(Path(ellipseIn: glowRect), with: .color(signal.opacity(0.2)))
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .contentShape(Rectangle())
        .onTapGesture { location in
            // Simplified marker hit testing
            selectedMarker = nil
        }
    }

    private func drawGridLines(context: GraphicsContext, width: CGFloat, height: CGFloat) {
        // Latitude lines every 30 degrees
        for lat in stride(from: -60.0, through: 60.0, by: 30.0) {
            let y = (90 - lat) / 180 * Double(height)
            let path = Path { p in
                p.move(to: CGPoint(x: 0, y: y))
                p.addLine(to: CGPoint(x: Double(width), y: y))
            }
            context.stroke(path, with: .color(textDim.opacity(0.3)), lineWidth: 0.5)
        }
        // Longitude lines every 30 degrees
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
        // Simplified continent shapes as filled polygons
        let continents: [(Color, [(Double, Double)])] = [
            // North America (simplified outline)
            (bgElev, [(-170,72),(-170,55),(-130,50),(-120,35),(-100,25),(-80,25),(-75,30),(-65,45),(-55,50),(-80,60),(-100,65),(-130,70),(-170,72)]),
            // South America
            (bgElev, [(-80,12),(-80,-5),(-75,-15),(-70,-25),(-65,-35),(-70,-55),(-75,-55),(-60,-35),(-50,-25),(-35,-5),(-50,5),(-60,10),(-80,12)]),
            // Europe
            (bgElev, [(-10,36),(0,38),(5,44),(10,48),(20,55),(30,60),(40,65),(30,70),(20,70),(10,65),(0,55),(-5,48),(-10,36)]),
            // Africa
            (bgElev, [(-15,35),(-5,30),(10,35),(35,30),(50,12),(40,0),(30,-15),(25,-35),(20,-35),(15,-25),(10,-5),(0,5),(-10,10),(-15,35)]),
            // Asia
            (bgElev, [(40,30),(50,40),(60,55),(80,60),(100,70),(130,65),(145,60),(150,55),(140,45),(130,35),(120,25),(100,25),(80,30),(60,35),(40,30)]),
            // Australia
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
