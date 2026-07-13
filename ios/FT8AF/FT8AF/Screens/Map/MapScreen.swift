import FT8Engine
import SwiftUI

enum MapProjection: String, CaseIterable {
    case equirectangular = "Standard"
    case azimuthal = "Azimuthal"
}

struct MapScreen: View {
    @Environment(AppState.self) private var appState
    @State private var scale: CGFloat = 1.0
    @State private var lastScale: CGFloat = 1.0
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero
    @State private var selectedMarker: MapMarker?
    @State private var projection: MapProjection = .equirectangular

    var body: some View {
        VStack(spacing: 0) {
            // Top bar
            HStack {
                Text("Map")
                    .font(.ft8afUI(size: 18, weight: .bold))
                    .foregroundStyle(textPrimary)

                Spacer()

                // Projection toggle
                Button {
                    withAnimation(.easeInOut(duration: 0.3)) {
                        projection = projection == .equirectangular ? .azimuthal : .equirectangular
                        resetView()
                    }
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: projection == .equirectangular ? "map" : "globe")
                            .font(.ft8afUI(size: 11, weight: .medium))
                        Text(projection.rawValue)
                            .font(.ft8afMono(size: 10, weight: .semibold))
                    }
                    .foregroundStyle(textMuted)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(
                        RoundedRectangle(cornerRadius: 6)
                            .fill(bgSurface3)
                    )
                }
                .buttonStyle(.plain)
                .padding(.trailing, 8)

                Text("\(markers.count) stations")
                    .font(.ft8afMono(size: 12, weight: .medium))
                    .foregroundStyle(textMuted)
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, 8)

            // Map canvas with gestures + zoom controls
            ZStack(alignment: .topTrailing) {
                Group {
                    if projection == .equirectangular {
                        WorldMapCanvas(
                            markers: markers,
                            selectedMarker: $selectedMarker,
                            operatorGrid: appState.settings.myGrid
                        )
                    } else {
                        AzimuthalMapCanvas(
                            markers: markers,
                            selectedMarker: $selectedMarker,
                            operatorGrid: appState.settings.myGrid
                        )
                    }
                }
                .scaleEffect(scale)
                .offset(offset)
                .gesture(
                    MagnifyGesture()
                        .onChanged { value in
                            scale = lastScale * value.magnification
                        }
                        .onEnded { _ in
                            lastScale = max(1.0, min(scale, 8.0))
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

                // Zoom controls
                VStack(spacing: 4) {
                    ZoomButton(icon: "plus") {
                        withAnimation(.easeOut(duration: 0.2)) {
                            lastScale = min(lastScale * 2, 8.0)
                            scale = lastScale
                        }
                    }
                    .disabled(lastScale >= 8.0)

                    ZoomButton(icon: "minus") {
                        withAnimation(.easeOut(duration: 0.2)) {
                            lastScale = max(lastScale / 2, 1.0)
                            scale = lastScale
                        }
                    }
                    .disabled(lastScale <= 1.0)

                    ZoomButton(icon: "1.circle") {
                        withAnimation(.easeOut(duration: 0.2)) {
                            resetView()
                        }
                    }
                }
                .padding(8)

                // Selected marker popup
                if let marker = selectedMarker {
                    markerPopup(marker)
                }
            }
        }
        .background(bgApp)
    }

    private func resetView() {
        scale = 1.0
        lastScale = 1.0
        offset = .zero
        lastOffset = .zero
    }

    @ViewBuilder
    private func markerPopup(_ marker: MapMarker) -> some View {
        VStack(spacing: 4) {
            Text(marker.callsign)
                .font(.ft8afMono(size: 14, weight: .bold))
                .foregroundStyle(textPrimary)
            Text(marker.grid)
                .font(.ft8afMono(size: 11, weight: .medium))
                .foregroundStyle(textMuted)

            HStack(spacing: 8) {
                if let snr = marker.snr {
                    Text("SNR: \(snr >= 0 ? "+\(snr)" : "\(snr)")")
                        .font(.ft8afMono(size: 10, weight: .medium))
                        .foregroundStyle(textFaint)
                }
                if let dist = markerDistance(marker) {
                    Text(dist)
                        .font(.ft8afMono(size: 10, weight: .medium))
                        .foregroundStyle(textFaint)
                }
                if let brg = markerBearing(marker) {
                    Text(brg)
                        .font(.ft8afMono(size: 10, weight: .medium))
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

    private func markerBearing(_ marker: MapMarker) -> String? {
        let myGrid = appState.settings.myGrid
        guard !myGrid.isEmpty, let myPos = gridToLatLon(myGrid) else { return nil }
        let brg = bearing(from: myPos, to: (marker.lat, marker.lon))
        return formatBearing(brg)
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

// MARK: - Zoom Button

private struct ZoomButton: View {
    let icon: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.ft8afUI(size: 13, weight: .semibold))
                .foregroundStyle(textPrimary)
                .frame(width: 32, height: 32)
                .background(
                    RoundedRectangle(cornerRadius: 6)
                        .fill(bgSurface2.opacity(0.9))
                        .overlay(
                            RoundedRectangle(cornerRadius: 6)
                                .strokeBorder(borderSubtle, lineWidth: 1)
                        )
                )
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Types

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

// `gridToLatLon` now lives in FT8Engine (see FT8AFKit/Sources/FT8Engine/Util.swift)
// so it decodes 6-character subsquares and is unit-tested; imported above.
