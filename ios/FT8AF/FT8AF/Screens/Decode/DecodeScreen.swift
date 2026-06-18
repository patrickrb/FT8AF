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
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(textPrimary)

                Spacer()

                // Compact mode toggle
                Button {
                    appState.decode.compactMode.toggle()
                } label: {
                    Image(systemName: decode.compactMode ? "list.bullet" : "list.bullet.indent")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(decode.compactMode ? accent : textMuted)
                }
                .buttonStyle(.plain)
                .padding(.trailing, 8)

                // Auto-clear toggle
                Button {
                    appState.decode.autoClear.toggle()
                } label: {
                    Image(systemName: decode.autoClear ? "arrow.clockwise.circle.fill" : "arrow.clockwise.circle")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(decode.autoClear ? accent : textMuted)
                }
                .buttonStyle(.plain)
                .padding(.trailing, 8)

                Text("\(decode.messages.count) msgs")
                    .font(.system(size: 12, weight: .medium, design: .monospaced))
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
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 0) {
                            ForEach(Array(groupedBySlot.enumerated()), id: \.offset) { groupIdx, group in
                                // Time divider between slot groups
                                if groupIdx > 0 {
                                    SlotDivider(time: group.first?.utcTime ?? "")
                                }

                                ForEach(group) { message in
                                    DecodeRow(
                                        message: message,
                                        myCall: appState.settings.myCall,
                                        workedCalls: workedCallSet,
                                        compact: decode.compactMode
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
                    .onChange(of: decode.messages.first?.id) { _, newId in
                        if let id = newId {
                            withAnimation(.easeOut(duration: 0.3)) {
                                proxy.scrollTo(id, anchor: .top)
                            }
                        }
                    }
                }
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
    }

    private var filteredMessages: [DecodeMessage] {
        let msgs = appState.decode.messages
        switch appState.decode.activeFilter {
        case .all:     return msgs
        case .cq:      return msgs.filter { $0.callTo == "CQ" || $0.callTo.hasPrefix("CQ ") }
        case .cqPota:  return msgs.filter { $0.extra.contains("K-") || $0.callTo == "CQ POTA" }
        case .newDxcc: return msgs
        case .needed:  return msgs
        case .forMe:   return msgs.filter { $0.callTo.uppercased() == appState.settings.myCall.uppercased() }
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

    private var workedCallSet: Set<String> {
        Set(appState.logbook.records.map { $0.call.uppercased() })
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: "waveform.slash")
                .font(.system(size: 40))
                .foregroundStyle(textFaint)
            Text("No messages")
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(textMuted)
            Text("Decoded FT8 messages will appear here")
                .font(.system(size: 13))
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
                .font(.system(size: 9, weight: .medium, design: .monospaced))
                .foregroundStyle(textFaint)
            Rectangle()
                .fill(textDim.opacity(0.4))
                .frame(height: 1)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
    }
}
