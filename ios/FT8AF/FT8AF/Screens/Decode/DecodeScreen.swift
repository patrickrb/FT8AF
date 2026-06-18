import SwiftUI

struct DecodeScreen: View {
    @Environment(AppState.self) private var appState
    @Environment(LiveEngine.self) private var engine
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
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(filteredMessages) { message in
                            DecodeRow(message: message)
                                .onTapGesture {
                                    appState.decode.selectedMessage = message
                                    showQsoSheet = true
                                }
                        }
                    }
                    .padding(.bottom, 160) // space for TxStrip + TabBar
                }
            }

            Spacer(minLength: 0)

            TxStrip(
                onHunt: { engine.toggleHunt() },
                onCallCQ: { engine.callCQ() },
                onStop: { engine.stopTx() },
                onToggleSlot: { engine.toggleSlotParity() }
            )
        }
        .background(bgApp)
        .sheet(isPresented: $showQsoSheet) {
            if let msg = decode.selectedMessage {
                QsoSheet(message: msg) { message in
                    engine.answerStation(message)
                }
                .presentationDetents([.medium])
                .presentationDragIndicator(.visible)
            }
        }
    }

    private var filteredMessages: [DecodeMessage] {
        let msgs = appState.decode.messages
        switch appState.decode.activeFilter {
        case .all:     return msgs
        case .cq:      return msgs.filter { $0.callTo == "CQ" }
        case .cqPota:  return msgs.filter { $0.extra.contains("K-") || $0.callTo == "CQ POTA" }
        case .newDxcc: return msgs // Phase 4: filter by DXCC lookup
        case .needed:  return msgs // Phase 4: filter by needed entities
        case .forMe:   return msgs.filter { $0.callTo == appState.settings.myCall }
        }
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
