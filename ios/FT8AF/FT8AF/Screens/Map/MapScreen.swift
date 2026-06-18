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
                WorldMapCanvas(
                    markers: markers,
                    selectedMarker: $selectedMarker,
                    operatorGrid: appState.settings.myGrid
                )
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
                    markerPopup(marker)
                }
            }
        }
        .background(bgApp)
    }

    @ViewBuilder
    private func markerPopup(_ marker: MapMarker) -> some View {
        VStack(spacing: 4) {
            Text(marker.callsign)
                .font(.system(size: 14, weight: .bold, design: .monospaced))
                .foregroundStyle(textPrimary)
            Text(marker.grid)
                .font(.system(size: 11, weight: .medium, design: .monospaced))
                .foregroundStyle(textMuted)

            HStack(spacing: 8) {
                if let snr = marker.snr {
                    Text("SNR: \(snr >= 0 ? "+\(snr)" : "\(snr)")")
                        .font(.system(size: 10, weight: .medium, design: .monospaced))
                        .foregroundStyle(textFaint)
                }
                if let dist = markerDistance(marker) {
                    Text(dist)
                        .font(.system(size: 10, weight: .medium, design: .monospaced))
                        .foregroundStyle(textFaint)
                }
            }
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

    private func markerDistance(_ marker: MapMarker) -> String? {
        let myGrid = appState.settings.myGrid
        guard !myGrid.isEmpty, let myPos = gridToLatLon(myGrid) else { return nil }
        let dist = haversineDistance(from: myPos, to: (marker.lat, marker.lon))
        return formatDistance(dist)
    }

    private var markers: [MapMarker] {
        let workedCalls = Set(appState.logbook.records.map { $0.call.uppercased() })
        let myCall = appState.settings.myCall.uppercased()

        return appState.decode.messages.compactMap { msg in
            guard !msg.grid.isEmpty else { return nil }
            guard let (lat, lon) = gridToLatLon(msg.grid) else { return nil }

            let status: MarkerStatus
            if !myCall.isEmpty && msg.callTo.uppercased() == myCall {
                status = .forMe
            } else if workedCalls.contains(msg.callFrom.uppercased()) {
                status = .worked
            } else if msg.callTo == "CQ" || msg.callTo.hasPrefix("CQ ") {
                status = .cq
            } else {
                status = .standard
            }

            return MapMarker(
                callsign: msg.callFrom,
                grid: msg.grid,
                lat: lat, lon: lon,
                snr: msg.snr,
                status: status
            )
        }
    }
}

enum MarkerStatus {
    case cq, worked, forMe, standard
}

struct MapMarker: Identifiable, Equatable {
    let id = UUID()
    let callsign: String
    let grid: String
    let lat: Double
    let lon: Double
    var snr: Int?
    var status: MarkerStatus = .standard

    static func == (lhs: MapMarker, rhs: MapMarker) -> Bool {
        lhs.id == rhs.id
    }
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
