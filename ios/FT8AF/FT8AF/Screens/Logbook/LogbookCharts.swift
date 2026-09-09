import FT8Engine
import SwiftUI

/// Rich stats view with donut chart, sparkline, grid heatmap, and award progress.
struct LogbookChartsView: View {
    let records: [QsoRecord]

    var body: some View {
        VStack(spacing: 16) {
            // Big stat cards row
            HStack(spacing: 10) {
                BigStatCard(title: "QSOs", value: records.count, color: accent)
                BigStatCard(title: "DXCC", value: uniqueDxccCount, color: signal)
                BigStatCard(title: "Grids", value: uniqueGridCount, color: target)
            }
            .padding(.horizontal, 16)

            // Band donut chart
            if !records.isEmpty {
                BandDonutChart(bandStats: bandStats)
                    .padding(.horizontal, 16)
            }

            // Signal strength sparkline
            if records.count >= 3 {
                SnrSparkline(records: records)
                    .padding(.horizontal, 16)
            }

            // Grid square heatmap
            if uniqueGridCount > 0 {
                GridHeatmap(records: records)
                    .padding(.horizontal, 16)
            }

            // Award progress
            AwardProgressSection(
                totalQsos: records.count,
                dxccCount: uniqueDxccCount,
                gridCount: uniqueGridCount
            )
            .padding(.horizontal, 16)
        }
    }

    private var bandStats: [(band: String, count: Int)] {
        var stats: [String: Int] = [:]
        for r in records { stats[r.band, default: 0] += 1 }
        let order = ["160M","80M","40M","30M","20M","17M","15M","12M","10M","6M"]
        return stats.sorted { a, b in
            let ai = order.firstIndex(of: a.key.uppercased()) ?? 99
            let bi = order.firstIndex(of: b.key.uppercased()) ?? 99
            return ai < bi
        }.map { (band: $0.key, count: $0.value) }
    }

    private var uniqueDxccCount: Int {
        // Approximate: unique callsign prefixes (first letter + digit)
        Set(records.map { dxccPrefix($0.call) }).count
    }

    private var uniqueGridCount: Int {
        Set(records.compactMap { $0.gridsquare.isEmpty ? nil : String($0.gridsquare.prefix(4).uppercased()) }).count
    }

    private func dxccPrefix(_ call: String) -> String {
        // Simple heuristic: first letter(s) up to first digit
        var prefix = ""
        for ch in call {
            if ch.isNumber { prefix.append(ch); break }
            prefix.append(ch)
        }
        return prefix
    }
}

// MARK: - Big Stat Card

private struct BigStatCard: View {
    let title: String
    let value: Int
    let color: Color

    var body: some View {
        VStack(spacing: 4) {
            Text("\(value)")
                .font(.ft8afMono(size: 22, weight: .bold))
                .foregroundStyle(textPrimary)
            Text(title)
                .font(.ft8afUI(size: 10, weight: .semibold))
                .foregroundStyle(textFaint)
                .textCase(.uppercase)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(bgSurface)
                .overlay(
                    RoundedRectangle(cornerRadius: 10)
                        .strokeBorder(color.opacity(0.2), lineWidth: 1)
                )
        )
    }
}

// MARK: - Band Donut Chart

private struct BandDonutChart: View {
    let bandStats: [(band: String, count: Int)]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("BAND DISTRIBUTION")
                .font(.ft8afMono(size: 10, weight: .bold))
                .foregroundStyle(textFaint)

            HStack(spacing: 16) {
                // Donut
                ZStack {
                    ForEach(Array(segments.enumerated()), id: \.offset) { _, seg in
                        DonutSegment(
                            startAngle: seg.start,
                            endAngle: seg.end,
                            color: seg.color
                        )
                    }
                    // Center label
                    VStack(spacing: 1) {
                        Text("\(total)")
                            .font(.ft8afMono(size: 16, weight: .bold))
                            .foregroundStyle(textPrimary)
                        Text("QSOs")
                            .font(.ft8afUI(size: 8, weight: .medium))
                            .foregroundStyle(textFaint)
                    }
                }
                .frame(width: 90, height: 90)

                // Legend
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(bandStats.prefix(6), id: \.band) { item in
                        HStack(spacing: 6) {
                            Circle()
                                .fill(bandColor(for: item.band))
                                .frame(width: 6, height: 6)
                            Text(item.band)
                                .font(.ft8afMono(size: 11, weight: .semibold))
                                .foregroundStyle(textMuted)
                            Spacer()
                            Text("\(item.count)")
                                .font(.ft8afMono(size: 11, weight: .bold))
                                .foregroundStyle(textPrimary)
                        }
                    }
                }
                .frame(maxWidth: .infinity)
            }
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(bgSurface)
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .strokeBorder(borderSubtle, lineWidth: 1)
                )
        )
    }

    private var total: Int { bandStats.reduce(0) { $0 + $1.count } }

    private var segments: [(start: Angle, end: Angle, color: Color)] {
        guard total > 0 else { return [] }
        var segs: [(start: Angle, end: Angle, color: Color)] = []
        var current = Angle.degrees(-90)
        for item in bandStats {
            let sweep = Angle.degrees(Double(item.count) / Double(total) * 360)
            segs.append((start: current, end: current + sweep, color: bandColor(for: item.band)))
            current = current + sweep
        }
        return segs
    }
}

private struct DonutSegment: Shape {
    let startAngle: Angle
    let endAngle: Angle
    let color: Color

    func path(in rect: CGRect) -> Path {
        let center = CGPoint(x: rect.midX, y: rect.midY)
        let outerRadius = min(rect.width, rect.height) / 2
        let innerRadius = outerRadius * 0.6

        var path = Path()
        path.addArc(center: center, radius: outerRadius,
                    startAngle: startAngle, endAngle: endAngle, clockwise: false)
        path.addArc(center: center, radius: innerRadius,
                    startAngle: endAngle, endAngle: startAngle, clockwise: true)
        path.closeSubpath()
        return path
    }
}

extension DonutSegment: View {
    var body: some View {
        self.fill(color)
    }
}

// MARK: - SNR Sparkline

private struct SnrSparkline: View {
    let records: [QsoRecord]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("SIGNAL TREND")
                    .font(.ft8afMono(size: 10, weight: .bold))
                    .foregroundStyle(textFaint)
                Spacer()
                Text("Last \(snrValues.count) QSOs")
                    .font(.ft8afMono(size: 9, weight: .medium))
                    .foregroundStyle(textDim)
            }

            Canvas { context, size in
                let values = snrValues
                guard values.count >= 2 else { return }

                let minSNR = Double(values.min() ?? -30)
                let maxSNR = Double(values.max() ?? 10)
                let range = max(maxSNR - minSNR, 1)
                let w = size.width
                let h = size.height
                let padding: CGFloat = 4

                // Zero line
                let zeroY = h - padding - ((0 - minSNR) / range) * (h - 2 * padding)
                let zeroPath = Path { p in
                    p.move(to: CGPoint(x: 0, y: zeroY))
                    p.addLine(to: CGPoint(x: w, y: zeroY))
                }
                context.stroke(zeroPath, with: .color(textDim.opacity(0.3)),
                             style: StrokeStyle(lineWidth: 0.5, dash: [3, 3]))

                // Sparkline
                let path = Path { p in
                    for (i, val) in values.enumerated() {
                        let x = padding + CGFloat(i) / CGFloat(values.count - 1) * (w - 2 * padding)
                        let y = h - padding - ((Double(val) - minSNR) / range) * (h - 2 * padding)
                        if i == 0 { p.move(to: CGPoint(x: x, y: y)) }
                        else { p.addLine(to: CGPoint(x: x, y: y)) }
                    }
                }
                context.stroke(path, with: .color(signal), lineWidth: 1.5)

                // Fill under
                var fillPath = path
                let lastX = padding + CGFloat(values.count - 1) / CGFloat(values.count - 1) * (w - 2 * padding)
                fillPath.addLine(to: CGPoint(x: lastX, y: h))
                fillPath.addLine(to: CGPoint(x: padding, y: h))
                fillPath.closeSubpath()
                context.fill(fillPath, with: .color(signal.opacity(0.08)))
            }
            .frame(height: 60)
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(bgSurface)
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .strokeBorder(borderSubtle, lineWidth: 1)
                )
        )
    }

    private var snrValues: [Int] {
        records.prefix(30).reversed().compactMap { r in
            Int(r.rstRcvd.trimmingCharacters(in: CharacterSet(charactersIn: "+-").inverted))
            ?? Int(r.rstRcvd)
        }
    }
}

// MARK: - Grid Heatmap

private struct GridHeatmap: View {
    let records: [QsoRecord]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("GRID COVERAGE")
                    .font(.ft8afMono(size: 10, weight: .bold))
                    .foregroundStyle(textFaint)
                Spacer()
                Text("\(workedFields.count)/324 fields")
                    .font(.ft8afMono(size: 9, weight: .medium))
                    .foregroundStyle(textDim)
            }

            Canvas { context, size in
                let cols = 18 // A-R
                let rows = 18 // A-R
                let cellW = size.width / CGFloat(cols)
                let cellH = size.height / CGFloat(rows)

                for row in 0..<rows {
                    for col in 0..<cols {
                        let field = "\(Character(UnicodeScalar(65 + col)!))\(Character(UnicodeScalar(65 + row)!))"
                        let rect = CGRect(x: CGFloat(col) * cellW, y: CGFloat(rows - 1 - row) * cellH,
                                         width: cellW - 0.5, height: cellH - 0.5)

                        let count = workedFields[field] ?? 0
                        let color: Color = count > 0 ? signal.opacity(min(0.3 + Double(count) * 0.15, 1.0)) : bgSurface3.opacity(0.3)
                        context.fill(Path(rect), with: .color(color))
                    }
                }
            }
            .frame(height: 100)
            .clipShape(RoundedRectangle(cornerRadius: 6))
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(bgSurface)
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .strokeBorder(borderSubtle, lineWidth: 1)
                )
        )
    }

    private var workedFields: [String: Int] {
        var fields: [String: Int] = [:]
        for r in records {
            let g = r.gridsquare.uppercased()
            guard g.count >= 2 else { continue }
            let field = String(g.prefix(2))
            fields[field, default: 0] += 1
        }
        return fields
    }
}

// MARK: - Award Progress

private struct AwardProgressSection: View {
    let totalQsos: Int
    let dxccCount: Int
    let gridCount: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("AWARDS PROGRESS")
                .font(.ft8afMono(size: 10, weight: .bold))
                .foregroundStyle(textFaint)

            AwardBar(name: "DXCC Mixed", current: dxccCount, goal: 100, color: signal)
            AwardBar(name: "VUCC Grids", current: gridCount, goal: 100, color: target)
            AwardBar(name: "WAS", current: min(totalQsos, 50), goal: 50, color: accent)
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(bgSurface)
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .strokeBorder(borderSubtle, lineWidth: 1)
                )
        )
    }
}

private struct AwardBar: View {
    let name: String
    let current: Int
    let goal: Int
    let color: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(name)
                    .font(.ft8afUI(size: 11, weight: .semibold))
                    .foregroundStyle(textMuted)
                Spacer()
                Text("\(current)/\(goal)")
                    .font(.ft8afMono(size: 10, weight: .bold))
                    .foregroundStyle(textFaint)
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 3)
                        .fill(bgSurface3)
                    RoundedRectangle(cornerRadius: 3)
                        .fill(
                            LinearGradient(
                                colors: [color, color.opacity(0.6)],
                                startPoint: .leading,
                                endPoint: .trailing
                            )
                        )
                        .frame(width: geo.size.width * min(CGFloat(current) / CGFloat(max(goal, 1)), 1.0))
                }
            }
            .frame(height: 6)
        }
    }
}
