import FT8Engine
import SwiftUI

/// Mini equirectangular map showing the great circle path between two grids.
struct QsoPathMap: View {
    let myGrid: String
    let theirGrid: String

    var body: some View {
        Canvas { ctx, size in
            let w = size.width
            let h = size.height

            // Background
            ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(bgSurface3))

            // Draw simplified continent outlines
            drawContinents(ctx: ctx, size: size)

            // Grid lines
            drawGridLines(ctx: ctx, size: size)

            guard let myPos = gridToLatLon(myGrid),
                  let theirPos = gridToLatLon(theirGrid) else { return }

            let myPt = project(lat: myPos.0, lon: myPos.1, in: size)
            let theirPt = project(lat: theirPos.0, lon: theirPos.1, in: size)

            // Great circle path (interpolated)
            drawGreatCircle(ctx: ctx, from: myPos, to: theirPos, size: size)

            // Station markers
            let myRect = CGRect(x: myPt.x - 4, y: myPt.y - 4, width: 8, height: 8)
            ctx.fill(Path(ellipseIn: myRect), with: .color(accent))
            ctx.stroke(Path(ellipseIn: myRect), with: .color(accent.opacity(0.5)), lineWidth: 2)

            let theirRect = CGRect(x: theirPt.x - 4, y: theirPt.y - 4, width: 8, height: 8)
            ctx.fill(Path(ellipseIn: theirRect), with: .color(signal))
            ctx.stroke(Path(ellipseIn: theirRect), with: .color(signal.opacity(0.5)), lineWidth: 2)

            // Labels
            let myLabel = Text(myGrid).font(.ft8afMono(size: 8, weight: .bold)).foregroundStyle(accent)
            let theirLabel = Text(theirGrid).font(.ft8afMono(size: 8, weight: .bold)).foregroundStyle(signal)

            let myLabelPt = CGPoint(x: max(20, min(w - 20, myPt.x)), y: max(10, myPt.y - 12))
            let theirLabelPt = CGPoint(x: max(20, min(w - 20, theirPt.x)), y: min(h - 10, theirPt.y + 12))

            ctx.draw(ctx.resolve(myLabel), at: myLabelPt, anchor: .center)
            ctx.draw(ctx.resolve(theirLabel), at: theirLabelPt, anchor: .center)
        }
        .frame(height: 100)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private func project(lat: Double, lon: Double, in size: CGSize) -> CGPoint {
        let x = (lon + 180) / 360 * size.width
        let y = (90 - lat) / 180 * size.height
        return CGPoint(x: x, y: y)
    }

    private func drawGridLines(ctx: GraphicsContext, size: CGSize) {
        var path = Path()
        // Longitude lines every 60 degrees
        for lon in stride(from: -180.0, through: 180.0, by: 60.0) {
            let x = (lon + 180) / 360 * size.width
            path.move(to: CGPoint(x: x, y: 0))
            path.addLine(to: CGPoint(x: x, y: size.height))
        }
        // Latitude lines every 30 degrees
        for lat in stride(from: -60.0, through: 60.0, by: 30.0) {
            let y = (90 - lat) / 180 * size.height
            path.move(to: CGPoint(x: 0, y: y))
            path.addLine(to: CGPoint(x: size.width, y: y))
        }
        ctx.stroke(path, with: .color(textDim.opacity(0.15)), lineWidth: 0.5)
    }

    private func drawGreatCircle(ctx: GraphicsContext, from a: (Double, Double), to b: (Double, Double), size: CGSize) {
        let steps = 40
        var path = Path()
        for i in 0...steps {
            let f = Double(i) / Double(steps)
            let pt = interpolateGC(from: a, to: b, fraction: f)
            let p = project(lat: pt.0, lon: pt.1, in: size)
            if i == 0 {
                path.move(to: p)
            } else {
                // Check for wrapping across the date line
                let prevPt = interpolateGC(from: a, to: b, fraction: Double(i - 1) / Double(steps))
                let prevP = project(lat: prevPt.0, lon: prevPt.1, in: size)
                if abs(p.x - prevP.x) > size.width * 0.5 {
                    path.move(to: p)
                } else {
                    path.addLine(to: p)
                }
            }
        }
        ctx.stroke(path, with: .color(accent.opacity(0.6)), style: StrokeStyle(lineWidth: 1.5, dash: [4, 3]))
    }

    private func interpolateGC(from a: (Double, Double), to b: (Double, Double), fraction f: Double) -> (Double, Double) {
        let lat1 = a.0 * .pi / 180, lon1 = a.1 * .pi / 180
        let lat2 = b.0 * .pi / 180, lon2 = b.1 * .pi / 180
        let d = 2 * asin(sqrt(
            pow(sin((lat2 - lat1) / 2), 2) + cos(lat1) * cos(lat2) * pow(sin((lon2 - lon1) / 2), 2)
        ))
        guard d > 0.001 else { return a }
        let A = sin((1 - f) * d) / sin(d)
        let B = sin(f * d) / sin(d)
        let x = A * cos(lat1) * cos(lon1) + B * cos(lat2) * cos(lon2)
        let y = A * cos(lat1) * sin(lon1) + B * cos(lat2) * sin(lon2)
        let z = A * sin(lat1) + B * sin(lat2)
        let lat = atan2(z, sqrt(x * x + y * y)) * 180 / .pi
        let lon = atan2(y, x) * 180 / .pi
        return (lat, lon)
    }

    private func drawContinents(ctx: GraphicsContext, size: CGSize) {
        // Simplified continent outlines as closed polygons
        let continents: [[(Double, Double)]] = [
            // North America (simplified)
            [(50,-130),(55,-120),(60,-100),(55,-75),(48,-65),(42,-70),(30,-85),(25,-80),(25,-100),(30,-115),(40,-125)],
            // South America
            [(10,-75),(5,-80),(-5,-80),(-15,-75),(-25,-65),(-35,-60),(-55,-70),(-50,-75),(-35,-72),(-20,-70),(-10,-80),(0,-80)],
            // Europe
            [(40,-10),(45,0),(48,5),(55,10),(60,25),(55,35),(50,40),(45,35),(40,25),(38,20),(36,0)],
            // Africa
            [(35,-5),(35,10),(30,30),(15,45),(5,42),(-5,40),(-15,35),(-25,30),(-35,20),(-30,15),(-15,10),(-5,5),(5,-5),(15,-17),(25,-15)],
            // Asia
            [(45,35),(50,60),(55,80),(60,100),(55,120),(50,130),(45,140),(35,130),(25,120),(20,105),(15,100),(25,70),(30,50)],
            // Australia
            [(-15,130),(-20,115),(-25,115),(-30,115),(-35,140),(-35,150),(-25,153),(-15,145)],
        ]

        for outline in continents {
            var path = Path()
            for (i, coord) in outline.enumerated() {
                let pt = project(lat: coord.0, lon: coord.1, in: size)
                if i == 0 { path.move(to: pt) } else { path.addLine(to: pt) }
            }
            path.closeSubpath()
            ctx.fill(path, with: .color(textDim.opacity(0.12)))
            ctx.stroke(path, with: .color(textDim.opacity(0.25)), lineWidth: 0.5)
        }
    }
}
