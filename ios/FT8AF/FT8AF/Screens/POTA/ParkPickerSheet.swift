import FT8Engine
import SwiftUI

/// Bottom-sheet park picker for the POTA Activate tab: recent activations and
/// nearby parks (ranked by distance) from pota.app's read API, with a text
/// filter. Selecting a park hands its reference back to fill the park-ref field;
/// free-text entry remains as a fallback.
///
/// Port of Android's `ParkPickerSheet`. Nearby uses the operator's configured
/// Maidenhead grid (Settings → `myGrid`) converted to lat/lon — iOS has no
/// separate location permission, so an unset/invalid grid degrades gracefully to
/// recents + manual entry (no prompt). Network failures are non-fatal: recents
/// stay usable and a soft error is shown for nearby.
struct ParkPickerSheet: View {
    /// Operator grid from Settings (e.g. "FN20"); "" when unset.
    let myGrid: String
    /// Raw (possibly comma-joined) park refs from activation history, newest first.
    let recentRefs: [String]
    let onSelect: (String) -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var filter = ""

    @State private var recentParks: [PotaPark] = []
    @State private var recentLoading = true

    @State private var nearbyParks: [PotaParkWithDistance] = []
    @State private var nearbyLoading = true
    /// nil while unknown; false when the grid can't provide coordinates.
    @State private var locationAvailable = true

    private var service: PotaParkService { PotaParkService.shared }

    var body: some View {
        VStack(spacing: 0) {
            // Grab handle + title
            HStack {
                Text("Search Parks")
                    .font(.ft8afUI(size: 16, weight: .bold))
                    .foregroundStyle(textPrimary)
                Spacer()
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.ft8afUI(size: 20))
                        .foregroundStyle(textFaint)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 16)
            .padding(.top, 16)
            .padding(.bottom, 10)

            // Search / filter field
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .font(.ft8afUI(size: 14))
                    .foregroundStyle(textMuted)
                TextField("Filter by name or reference", text: $filter)
                    .font(.ft8afUI(size: 14))
                    .foregroundStyle(textPrimary)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(
                RoundedRectangle(cornerRadius: 10).fill(bgSurface)
                    .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(borderSubtle, lineWidth: 1))
            )
            .padding(.horizontal, 16)
            .padding(.bottom, 8)

            ScrollView {
                VStack(alignment: .leading, spacing: 6) {
                    sectionHeader("RECENT PARKS")
                    recentSection
                    Spacer().frame(height: 10)
                    sectionHeader("NEARBY PARKS")
                    nearbySection
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 16)
                .padding(.top, 6)
            }
        }
        .background(bgApp)
        .task { await load() }
    }

    // MARK: - Sections

    @ViewBuilder
    private var recentSection: some View {
        if recentLoading {
            loadingRow("Loading recent parks…")
        } else {
            let filtered = PotaParks.filterParks(recentParks, query: filter)
            if filtered.isEmpty {
                emptyRow("No recent parks")
            } else {
                ForEach(filtered) { park in
                    parkRow(reference: park.reference, name: park.name, subtitle: park.locationDesc)
                }
            }
        }
    }

    @ViewBuilder
    private var nearbySection: some View {
        if nearbyLoading {
            loadingRow("Finding nearby parks…")
        } else if !locationAvailable {
            emptyRow("Set your grid in Settings to see nearby parks")
        } else {
            let filtered = PotaParks.filterNearbyParks(nearbyParks, query: filter)
            if filtered.isEmpty {
                emptyRow("No nearby parks")
            } else {
                ForEach(filtered) { item in
                    parkRow(
                        reference: item.park.reference,
                        name: item.park.name,
                        subtitle: formatQsoDistance(km: item.distanceKm, inMiles: false))
                }
            }
        }
    }

    // MARK: - Rows

    private func sectionHeader(_ text: String) -> some View {
        Text(text)
            .font(.ft8afMono(size: 11, weight: .semibold))
            .foregroundStyle(textMuted)
            .padding(.leading, 2)
    }

    private func parkRow(reference: String, name: String, subtitle: String) -> some View {
        Button {
            onSelect(reference)
            dismiss()
        } label: {
            HStack(spacing: 8) {
                Text(reference)
                    .font(.ft8afMono(size: 11, weight: .semibold))
                    .foregroundStyle(statusConfirmed)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(
                        RoundedRectangle(cornerRadius: 6).fill(bgSurface2)
                            .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(borderSubtle, lineWidth: 1))
                    )
                VStack(alignment: .leading, spacing: 1) {
                    if !name.isEmpty {
                        Text(name)
                            .font(.ft8afUI(size: 13))
                            .foregroundStyle(textPrimary)
                            .lineLimit(1)
                    }
                    if !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.ft8afUI(size: 11))
                            .foregroundStyle(textMuted)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 10).fill(bgSurface)
                    .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(borderSubtle, lineWidth: 1))
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func loadingRow(_ message: String) -> some View {
        HStack(spacing: 8) {
            ProgressView().tint(accent)
            Text(message)
                .font(.ft8afUI(size: 12))
                .foregroundStyle(textMuted)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
    }

    private func emptyRow(_ message: String) -> some View {
        Text(message)
            .font(.ft8afUI(size: 12))
            .foregroundStyle(textMuted)
            .padding(.horizontal, 2)
            .padding(.vertical, 8)
    }

    // MARK: - Loading

    private func load() async {
        // Recent parks — resolve details for recently-activated refs.
        recentParks = await service.recentParksWithDetails(rawRefs: recentRefs)
        recentLoading = false

        // Nearby parks — derive coordinates from the operator's grid. No grid
        // (or a malformed one) → degrade to recents + manual entry, no prompt.
        if let coord = gridToLatLon(myGrid) {
            nearbyParks = await service.nearbyParks(userLat: coord.0, userLng: coord.1)
        } else {
            locationAvailable = false
        }
        nearbyLoading = false
    }
}
