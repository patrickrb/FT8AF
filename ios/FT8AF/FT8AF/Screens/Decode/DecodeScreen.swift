import FT8Engine
import SwiftUI

struct DecodeScreen: View {
    @Environment(AppState.self) private var appState
    @State private var showQsoSheet = false

    var body: some View {
        let decode = appState.decode

        VStack(spacing: 0) {
            // Top bar
            HStack {
                Text("Decode")
                    .font(.ft8afUI(size: 18, weight: .bold))
                    .foregroundStyle(textPrimary)

                Spacer()

                // Compact mode toggle
                Button {
                    appState.decode.compactMode.toggle()
                } label: {
                    Image(systemName: decode.compactMode ? "list.bullet" : "list.bullet.indent")
                        .font(.ft8afUI(size: 14, weight: .medium))
                        .foregroundStyle(decode.compactMode ? accent : textMuted)
                }
                .buttonStyle(.plain)
                .padding(.trailing, 8)

                // Auto-clear toggle
                Button {
                    appState.decode.autoClear.toggle()
                } label: {
                    Image(systemName: decode.autoClear ? "arrow.clockwise.circle.fill" : "arrow.clockwise.circle")
                        .font(.ft8afUI(size: 14, weight: .medium))
                        .foregroundStyle(decode.autoClear ? accent : textMuted)
                }
                .buttonStyle(.plain)
                .padding(.trailing, 8)

                // Clear all
                Button {
                    appState.decode.messages.removeAll()
                } label: {
                    Image(systemName: "trash")
                        .font(.ft8afUI(size: 13, weight: .medium))
                        .foregroundStyle(textMuted)
                }
                .buttonStyle(.plain)
                .padding(.trailing, 8)

                Text("\(decode.messages.count) msgs")
                    .font(.ft8afMono(size: 12, weight: .medium))
                    .foregroundStyle(textMuted)
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, 8)

            // Filter chips
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(DecodeFilter.allCases, id: \.self) { filter in
                        FilterChip(
                            label: filter.rawValue,
                            isSelected: decode.activeFilter == filter
                        ) {
                            appState.decode.activeFilter = filter
                        }
                    }
                }
                .padding(.horizontal, 16)
            }
            .padding(.bottom, 8)

            // Message list
            if filteredMessages.isEmpty {
                emptyState
            } else {
                messageList
            }

            Spacer(minLength: 0)
        }
        .background(bgApp)
        .sheet(isPresented: $showQsoSheet) {
            if let msg = decode.selectedMessage {
                QsoSheet(message: msg) { message in
                    appState.engine?.answerStation(message)
                }
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
            }
        }
        // Auto-open QSO sheet when our CQ is answered.
        .onChange(of: appState.tx.autoOpenMessage?.id) { _, newId in
            if let msg = appState.tx.autoOpenMessage, newId != nil {
                appState.decode.selectedMessage = msg
                showQsoSheet = true
                appState.tx.autoOpenMessage = nil
            }
        }
        // Auto-close QSO sheet after completion (5 second delay).
        .onChange(of: appState.tx.qsoCompletedAt) { _, newVal in
            if newVal != nil && showQsoSheet {
                DispatchQueue.main.asyncAfter(deadline: .now() + 5.0) {
                    showQsoSheet = false
                }
            }
        }
    }

    // MARK: - List

    private var messageList: some View {
        let decode = appState.decode
        let settings = appState.settings
        let index = logbookIndex
        let toggles = HighlightToggles(
            newDxcc: settings.highlightNewDxcc,
            newGrid: settings.highlightNewGrid,
            newBand: settings.highlightNewBand,
            worked: settings.highlightWorked
        )
        let targetCall = appState.tx.targetCall.uppercased()

        return ScrollViewReader { proxy in
            ScrollView {
                // The 1 s timeline drives the relative-age labels
                // ("now" → "32s" → "5m") without any per-row timers.
                TimelineView(.periodic(from: .now, by: 1)) { timeline in
                    LazyVStack(spacing: 0) {
                        ForEach(Array(groupedBySlot.enumerated()), id: \.offset) { groupIdx, group in
                            // Time divider between slot groups
                            if groupIdx > 0 {
                                SlotDivider(time: group.first?.utcTime ?? "")
                            }

                            ForEach(group) { message in
                                DecodeRow(
                                    message: message,
                                    myCall: settings.myCall,
                                    myGrid: settings.myGrid,
                                    highlight: classifyDecode(
                                        callFrom: message.callFrom,
                                        callTo: message.callTo,
                                        grid: message.grid,
                                        extra: message.extra,
                                        myCall: settings.myCall,
                                        band: settings.band,
                                        index: index,
                                        toggles: toggles
                                    ),
                                    isTarget: !targetCall.isEmpty &&
                                        message.callFrom.uppercased() == targetCall,
                                    compact: decode.compactMode,
                                    now: timeline.date,
                                    distanceInMiles: settings.distanceInMiles
                                )
                                .id(message.id)
                                .onTapGesture {
                                    appState.decode.selectedMessage = message
                                    showQsoSheet = true
                                }
                            }
                        }
                    }
                    .padding(.bottom, 160)
                }
            }
            .onChange(of: decode.messages.first?.id) { _, newId in
                if let id = newId {
                    withAnimation(.easeOut(duration: 0.3)) {
                        proxy.scrollTo(id, anchor: .top)
                    }
                }
            }
        }
    }

    // MARK: - Filtering

    /// Pre-computed logbook lookups shared by highlight classification and the
    /// New DXCC / Needed chips.
    private var logbookIndex: LogbookIndex {
        LogbookIndex(qsos: appState.logbook.records.map {
            LoggedQso(call: $0.call, grid: $0.gridsquare, band: $0.band)
        })
    }

    /// Base stage (always-on): blocklist + the persisted settings filters
    /// (Show Only CQ, DX Only, continent). These AND with whatever chip is
    /// selected, mirroring Android `filterMessages`.
    private var baseMessages: [DecodeMessage] {
        let settings = appState.settings
        let blocked = Set(settings.blockedCallsigns.map { $0.uppercased() })
        var msgs = blocked.isEmpty ? appState.decode.messages :
            appState.decode.messages.filter { !blocked.contains($0.callFrom.uppercased()) }

        if settings.showOnlyCQ {
            msgs = msgs.filter { isCQMessage(callTo: $0.callTo) }
        }
        if settings.dxOnly {
            // DX = station's continent known and different from mine (derived
            // from my call prefix). Unknown continents are conservatively kept
            // out, matching the Android predicate.
            let myContinent = DxccPrefix.continent(for: settings.myCall)
            msgs = msgs.filter {
                guard let mine = myContinent,
                      let theirs = DxccPrefix.continent(for: $0.callFrom) else { return false }
                return theirs != mine
            }
        }
        if settings.continentFilter != "All" {
            msgs = msgs.filter {
                DxccPrefix.continent(for: $0.callFrom) == settings.continentFilter
            }
        }
        return msgs
    }

    private var filteredMessages: [DecodeMessage] {
        let msgs = baseMessages

        switch appState.decode.activeFilter {
        case .all:     return msgs
        case .cq:      return msgs.filter { isCQMessage(callTo: $0.callTo) }
        case .cqPota:  return msgs.filter { isPotaCq(callTo: $0.callTo, extra: $0.extra) }
        case .newDxcc:
            // Stations whose DXCC entity isn't in the logbook yet.
            let worked = logbookIndex.workedEntities
            return msgs.filter {
                guard let entity = DxccPrefix.entity(for: $0.callFrom) else { return true }
                return !worked.contains(entity.name)
            }
        case .needed:
            // CQ stations not yet worked.
            let workedCalls = logbookIndex.workedCalls
            return msgs.filter {
                isCQMessage(callTo: $0.callTo) &&
                !workedCalls.contains($0.callFrom.uppercased())
            }
        case .forMe:
            return msgs.filter {
                isDirectedToMe(callTo: $0.callTo, myCall: appState.settings.myCall)
            }
        }
    }

    /// Group filtered messages by slotIndex for time dividers.
    private var groupedBySlot: [[DecodeMessage]] {
        var groups: [[DecodeMessage]] = []
        var currentSlot: Int?
        for msg in filteredMessages {
            if msg.slotIndex != currentSlot {
                groups.append([msg])
                currentSlot = msg.slotIndex
            } else {
                groups[groups.count - 1].append(msg)
            }
        }
        return groups
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: "waveform.slash")
                .font(.ft8afUI(size: 40))
                .foregroundStyle(textFaint)
            Text("No messages")
                .font(.ft8afUI(size: 16, weight: .medium))
                .foregroundStyle(textMuted)
            Text("Decoded FT8 messages will appear here")
                .font(.ft8afUI(size: 13))
                .foregroundStyle(textFaint)
            Spacer()
        }
    }
}

/// Thin time divider line between slot groups.
private struct SlotDivider: View {
    let time: String

    var body: some View {
        HStack(spacing: 8) {
            Rectangle()
                .fill(textDim.opacity(0.4))
                .frame(height: 1)
            Text(String(time.prefix(5)))
                .font(.ft8afMono(size: 9, weight: .medium))
                .foregroundStyle(textFaint)
            Rectangle()
                .fill(textDim.opacity(0.4))
                .frame(height: 1)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
    }
}
