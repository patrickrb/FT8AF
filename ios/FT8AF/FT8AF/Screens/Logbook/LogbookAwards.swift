import FT8Engine
import SwiftUI

/// Awards tracking tab with progress toward common amateur radio awards.
struct LogbookAwardsView: View {
    let records: [QsoRecord]

    var body: some View {
        VStack(spacing: 16) {
            // Summary stat cards
            HStack(spacing: 10) {
                AwardStatCard(title: "DXCC", value: dxccCount, goal: 100, color: signal)
                AwardStatCard(title: "Grids", value: gridCount, goal: 100, color: target)
                AwardStatCard(title: "Zones", value: zoneCount, goal: 40, color: accent)
            }
            .padding(.horizontal, 16)

            // Award progress cards
            VStack(spacing: 12) {
                AwardCard(
                    name: "DXCC Mixed",
                    description: "Work 100 unique DXCC entities on any band/mode",
                    current: dxccCount,
                    milestones: [100, 200, 300, 340],
                    color: signal,
                    icon: "globe"
                )

                AwardCard(
                    name: "WAS — Worked All States",
                    description: "Confirm contacts with all 50 US states",
                    current: stateCount,
                    milestones: [50],
                    color: accent,
                    icon: "flag"
                )

                AwardCard(
                    name: "WAZ — Worked All Zones",
                    description: "Confirm contacts in all 40 CQ zones",
                    current: zoneCount,
                    milestones: [40],
                    color: statusNew,
                    icon: "map"
                )

                AwardCard(
                    name: "VUCC Grid Squares",
                    description: "Work unique Maidenhead grid squares (VHF/UHF)",
                    current: gridCount,
                    milestones: [100, 200, 500],
                    color: target,
                    icon: "square.grid.3x3"
                )

                AwardCard(
                    name: "DXCC Challenge",
                    description: "Unique band-entity combinations across 5 bands",
                    current: bandEntityCount,
                    milestones: [500, 1000, 1500, 2000],
                    color: statusWarn,
                    icon: "star"
                )
            }
            .padding(.horizontal, 16)
        }
        .padding(.top, 8)
    }

    // MARK: - Computed Stats

    private var dxccCount: Int {
        Set(records.map { dxccPrefix($0.call) }).count
    }

    private var gridCount: Int {
        Set(records.compactMap {
            $0.gridsquare.isEmpty ? nil : String($0.gridsquare.prefix(4).uppercased())
        }).count
    }

    private var stateCount: Int {
        // Approximate: count unique 2-letter field codes from US callsigns
        let usRecords = records.filter { isUSCall($0.call) }
        return Set(usRecords.compactMap {
            $0.gridsquare.isEmpty ? nil : String($0.gridsquare.prefix(2).uppercased())
        }).count
    }

    private var zoneCount: Int {
        // Approximate from grid: count unique longitude bands (18 CQ zones cover ~20 deg each)
        Set(records.compactMap { r -> Int? in
            guard let (_, lon) = gridToLatLon(r.gridsquare) else { return nil }
            return Int((lon + 180) / 20) + 1
        }).count
    }

    private var bandEntityCount: Int {
        // Count unique (band, DXCC prefix) combinations
        Set(records.map { "\($0.band)|\(dxccPrefix($0.call))" }).count
    }

    private func dxccPrefix(_ call: String) -> String {
        var prefix = ""
        for ch in call {
            if ch.isNumber { prefix.append(ch); break }
            prefix.append(ch)
        }
        return prefix
    }

    private func isUSCall(_ call: String) -> Bool {
        let upper = call.uppercased()
        return upper.hasPrefix("K") || upper.hasPrefix("W") || upper.hasPrefix("N") || upper.hasPrefix("A")
    }
}

// MARK: - Award Stat Card

private struct AwardStatCard: View {
    let title: String
    let value: Int
    let goal: Int
    let color: Color

    var body: some View {
        VStack(spacing: 6) {
            ZStack {
                Circle()
                    .stroke(bgSurface3, lineWidth: 3)
                Circle()
                    .trim(from: 0, to: min(CGFloat(value) / CGFloat(goal), 1.0))
                    .stroke(color, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    .rotationEffect(.degrees(-90))

                Text("\(value)")
                    .font(.system(size: 18, weight: .bold, design: .monospaced))
                    .foregroundStyle(textPrimary)
            }
            .frame(width: 54, height: 54)

            Text(title)
                .font(.system(size: 10, weight: .semibold))
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

// MARK: - Award Card

private struct AwardCard: View {
    let name: String
    let description: String
    let current: Int
    let milestones: [Int]
    let color: Color
    let icon: String

    private var nextMilestone: Int {
        milestones.first { $0 > current } ?? milestones.last ?? 100
    }

    private var progress: CGFloat {
        min(CGFloat(current) / CGFloat(max(nextMilestone, 1)), 1.0)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(color)
                    .frame(width: 28, height: 28)
                    .background(
                        RoundedRectangle(cornerRadius: 6)
                            .fill(color.opacity(0.14))
                    )

                VStack(alignment: .leading, spacing: 1) {
                    Text(name)
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(textPrimary)
                    Text(description)
                        .font(.system(size: 10))
                        .foregroundStyle(textFaint)
                        .lineLimit(1)
                }

                Spacer()

                Text("\(current)/\(nextMilestone)")
                    .font(.system(size: 12, weight: .bold, design: .monospaced))
                    .foregroundStyle(progress >= 1.0 ? statusConfirmed : textMuted)
            }

            // Progress bar
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 4)
                        .fill(bgSurface3)
                    RoundedRectangle(cornerRadius: 4)
                        .fill(
                            LinearGradient(
                                colors: [color, color.opacity(0.6)],
                                startPoint: .leading,
                                endPoint: .trailing
                            )
                        )
                        .frame(width: geo.size.width * progress)
                }
            }
            .frame(height: 8)

            // Milestone markers
            if milestones.count > 1 {
                HStack(spacing: 0) {
                    ForEach(milestones, id: \.self) { ms in
                        Text("\(ms)")
                            .font(.system(size: 8, weight: .semibold, design: .monospaced))
                            .foregroundStyle(current >= ms ? color : textDim)
                        if ms != milestones.last {
                            Spacer()
                        }
                    }
                }
            }
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(bgSurface)
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .strokeBorder(progress >= 1.0 ? color.opacity(0.4) : borderSubtle, lineWidth: 1)
                )
        )
    }
}
