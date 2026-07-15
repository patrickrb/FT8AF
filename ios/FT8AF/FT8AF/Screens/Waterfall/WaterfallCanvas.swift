import SwiftUI
import FT8Audio

/// Live scrolling waterfall heatmap, driven by `appState.waterfall.rows`.
/// Each row is a `[UInt8]` of brightness values (0...255) produced by
/// `WaterfallRowBuilder`. Renders newest rows at the bottom.
/// When message marking is enabled, decoded callsign labels are drawn at
/// their frequency positions. Rows carrying a UTC boundary label (the first
/// row of each 15 s FT8 period) get a subtle divider line plus the period
/// start time at the left edge, matching Android's slot gridline.
struct WaterfallCanvas: View {
    @Environment(AppState.self) private var appState

    /// Top of the displayed band — the span the drawn rows actually cover
    /// (`waterfall.displayMaxHz`, maintained by the waterfall loop). All
    /// overlays map frequencies against this span so they sit on the trace
    /// even when the loop is stopped and the settings width has changed.
    private var displayMaxHz: Float { appState.waterfall.displayMaxHz }

    var body: some View {
        Canvas { context, size in
            let rows = appState.waterfall.rows
            guard !rows.isEmpty else { return }
            let numRows = rows.count
            let numCols = rows[0].count
            guard numCols > 0 else { return }

            let cellW = size.width / CGFloat(numCols)
            let cellH = size.height / CGFloat(numRows)

            for r in 0..<numRows {
                let row = rows[r]
                for c in 0..<min(numCols, row.count) {
                    let value = row[c]
                    let color = waterfallColor(value)
                    let rect = CGRect(
                        x: CGFloat(c) * cellW,
                        y: CGFloat(r) * cellH,
                        width: cellW + 1,
                        height: cellH + 1
                    )
                    context.fill(Path(rect), with: .color(color))
                }
            }

            // UTC period-boundary dividers + labels (rows flagged by the
            // engine's timestamp gate, one per 15 s slot).
            drawPeriodTimestamps(context: context, size: size, numRows: numRows, cellH: cellH)

            // Draw message frequency labels when enabled
            if appState.waterfall.messageMarking {
                drawMessageLabels(context: context, size: size, numCols: numCols)
            }

            // Draw TX frequency marker. Map with the same span the waterfall
            // columns cover (the spectrum-width setting) so the marker sits on
            // the trace.
            let txFreq = appState.waterfall.txFreqHz
            let txX = CGFloat(WaterfallAxis.clampedFraction(
                forHz: txFreq, displayMaxHz: displayMaxHz
            )) * size.width
            let txPath = Path { p in
                p.move(to: CGPoint(x: txX, y: 0))
                p.addLine(to: CGPoint(x: txX, y: size.height))
            }
            context.stroke(txPath, with: .color(accent.opacity(0.5)),
                           style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
        }
        .background(bgApp)
        .clipShape(RoundedRectangle(cornerRadius: 4))
    }

    /// Divider line + "HH:mm:ss" label on each row that starts a new FT8
    /// period. Left edge, faint monospaced caption — Android's look.
    private func drawPeriodTimestamps(
        context: GraphicsContext, size: CGSize, numRows: Int, cellH: CGFloat
    ) {
        let stamps = appState.waterfall.rowTimestamps
        guard !stamps.isEmpty else { return }

        for r in 0..<min(numRows, stamps.count) {
            guard let label = stamps[r] else { continue }
            let y = CGFloat(r) * cellH

            // Subtle horizontal divider across the full width.
            let divider = Path { p in
                p.move(to: CGPoint(x: 0, y: y))
                p.addLine(to: CGPoint(x: size.width, y: y))
            }
            context.stroke(divider, with: .color(textFaint.opacity(0.4)), lineWidth: 0.5)

            // Period-start time at the left edge, just below the divider.
            context.draw(
                Text(label)
                    .font(.ft8afMono(size: 9, weight: .medium))
                    .foregroundStyle(textFaint),
                at: CGPoint(x: 3, y: y + 2),
                anchor: .topLeading
            )
        }
    }

    private func drawMessageLabels(context: GraphicsContext, size: CGSize, numCols: Int) {
        let messages = appState.decode.messages
        guard !messages.isEmpty else { return }

        // Only show unique callsigns at unique frequencies (latest per callsign)
        var seen = Set<String>()
        let uniqueMessages = messages.prefix(20).filter { msg in
            let key = msg.callFrom.uppercased()
            if seen.contains(key) { return false }
            seen.insert(key)
            return true
        }

        for msg in uniqueMessages {
            let x = CGFloat(WaterfallAxis.fraction(
                forHz: msg.freqHz, displayMaxHz: displayMaxHz
            )) * size.width
            guard x > 0, x < size.width else { continue }

            let label = context.resolve(Text(msg.callFrom)
                .font(.ft8afMono(size: 8, weight: .bold))
                .foregroundStyle(labelColor(msg)))
            context.draw(label, at: CGPoint(x: x, y: 8), anchor: .top)

            // Frequency tick mark
            let tick = Path { p in
                p.move(to: CGPoint(x: x, y: 18))
                p.addLine(to: CGPoint(x: x, y: 24))
            }
            context.stroke(tick, with: .color(labelColor(msg).opacity(0.5)), lineWidth: 1)
        }
    }

    private func labelColor(_ msg: DecodeMessage) -> Color {
        if msg.callTo == "CQ" || msg.callTo.hasPrefix("CQ ") { return accent }
        let myCall = appState.settings.myCall.uppercased()
        if !myCall.isEmpty && msg.callTo.uppercased() == myCall { return target }
        return signal
    }

    private func waterfallColor(_ value: UInt8) -> Color {
        let v = Double(value) / 255.0
        if v < 0.25 {
            let t = v / 0.25
            return Color(red: 0, green: 0, blue: 0.2 + 0.8 * t)
        } else if v < 0.5 {
            let t = (v - 0.25) / 0.25
            return Color(red: 0, green: t, blue: 1.0 - t)
        } else if v < 0.75 {
            let t = (v - 0.5) / 0.25
            return Color(red: t, green: 1.0, blue: 0)
        } else {
            let t = (v - 0.75) / 0.25
            return Color(red: 1.0, green: 1.0 - t, blue: 0)
        }
    }
}
