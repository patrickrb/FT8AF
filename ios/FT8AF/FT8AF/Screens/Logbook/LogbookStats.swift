import SwiftUI

/// Stats cards shown at the top of the Logbook screen.
struct LogbookStats: View {
    let totalQsos: Int
    let bandStats: [String: Int]

    var body: some View {
        VStack(spacing: 10) {
            // Total QSOs card
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Total QSOs")
                        .font(.ft8afUI(size: 11, weight: .medium))
                        .foregroundStyle(textFaint)
                        .textCase(.uppercase)
                    Text("\(totalQsos)")
                        .font(.ft8afMono(size: 28, weight: .bold))
                        .foregroundStyle(textPrimary)
                }
                Spacer()
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .font(.ft8afUI(size: 24))
                    .foregroundStyle(accent.opacity(0.5))
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

            // Band breakdown
            if !bandStats.isEmpty {
                LazyVGrid(columns: [
                    GridItem(.flexible()),
                    GridItem(.flexible()),
                    GridItem(.flexible()),
                ], spacing: 8) {
                    ForEach(sortedBands, id: \.0) { band, count in
                        BandStatCard(band: band, count: count)
                    }
                }
            }
        }
    }

    private var sortedBands: [(String, Int)] {
        let order = ["160M", "80M", "40M", "30M", "20M", "17M", "15M", "12M", "10M", "6M"]
        return bandStats.sorted { a, b in
            let ai = order.firstIndex(of: a.key.uppercased()) ?? 99
            let bi = order.firstIndex(of: b.key.uppercased()) ?? 99
            return ai < bi
        }
    }
}

private struct BandStatCard: View {
    let band: String
    let count: Int

    var body: some View {
        VStack(spacing: 4) {
            Text(band)
                .font(.ft8afMono(size: 11, weight: .bold))
                .foregroundStyle(bandColor(for: band))
            Text("\(count)")
                .font(.ft8afMono(size: 16, weight: .semibold))
                .foregroundStyle(textPrimary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: 8)
                .fill(bgSurface)
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .strokeBorder(bandColor(for: band).opacity(0.2), lineWidth: 1)
                )
        )
    }
}
