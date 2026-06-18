import SwiftUI

struct MapScreen: View {
    @Environment(AppState.self) private var appState
    @State private var scale: CGFloat = 1.0
    @State private var lastScale: CGFloat = 1.0
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero
    @State private var selectedMarker: MapMarker?

    var body: some View {
        VStack(spacing: 0) {
            // Top bar
            HStack {
                Text("Map")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(textPrimary)
                Spacer()
                Text("\(markers.count) stations")
                    .font(.system(size: 12, weight: .medium, design: .monospaced))
                    .foregroundStyle(textMuted)
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, 8)

            // Map canvas with gestures
            ZStack {
                WorldMapCanvas(markers: markers, selectedMarker: $selectedMarker)
                    .scaleEffect(scale)
                    .offset(offset)
                    .gesture(
                        MagnifyGesture()
                            .onChanged { value in
                                scale = lastScale * value.magnification
                            }
                            .onEnded { _ in
                                lastScale = max(1.0, min(scale, 5.0))
                                scale = lastScale
                            }
                    )
                    .simultaneousGesture(
                        DragGesture()
                            .onChanged { value in
                                offset = CGSize(
                                    width: lastOffset.width + value.translation.width,
                                    height: lastOffset.height + value.translation.height
                                )
                            }
                            .onEnded { _ in
                                lastOffset = offset
                            }
                    )

                // Selected marker popup
                if let marker = selectedMarker {
                    VStack(spacing: 4) {
                        Text(marker.callsign)
                            .font(.system(size: 14, weight: .bold, design: .monospaced))
                            .foregroundStyle(textPrimary)
                        Text(marker.grid)
                            .font(.system(size: 11, weight: .medium, design: .monospaced))
                            .foregroundStyle(textMuted)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(
                        RoundedRectangle(cornerRadius: 8)
                            .fill(bgSurface2)
                            .overlay(
                                RoundedRectangle(cornerRadius: 8)
                                    .strokeBorder(borderStrong, lineWidth: 1)
                            )
                    )
                    .position(x: 100, y: 40)
                    .onTapGesture {
                        selectedMarker = nil
                    }
                }
            }
        }
        .background(bgApp)
    }

    private var markers: [MapMarker] {
        appState.decode.messages.compactMap { msg in
            guard !msg.grid.isEmpty else { return nil }
            guard let (lat, lon) = gridToLatLon(msg.grid) else { return nil }
            return MapMarker(callsign: msg.callFrom, grid: msg.grid, lat: lat, lon: lon)
        }
    }
}

struct MapMarker: Identifiable, Equatable {
    let id = UUID()
    let callsign: String
    let grid: String
    let lat: Double
    let lon: Double
}

/// Convert 4-char Maidenhead grid to lat/lon center.
func gridToLatLon(_ grid: String) -> (Double, Double)? {
    let chars = Array(grid.uppercased().utf8)
    guard chars.count >= 4,
          chars[0] >= 65, chars[0] <= 82, // A-R
          chars[1] >= 65, chars[1] <= 82,
          chars[2] >= 48, chars[2] <= 57, // 0-9
          chars[3] >= 48, chars[3] <= 57 else { return nil }
    let lon = Double(chars[0] - 65) * 20 + Double(chars[2] - 48) * 2 + 1 - 180
    let lat = Double(chars[1] - 65) * 10 + Double(chars[3] - 48) * 1 + 0.5 - 90
    return (lat, lon)
}
