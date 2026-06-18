import SwiftUI
import FT8DSP

struct RoundTripView: View {
    @State private var vm = RoundTripViewModel()

    var body: some View {
        NavigationStack {
            Form {
                Section("Input") {
                    TextField("FT8 Message", text: $vm.message)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()

                    HStack {
                        Text("Base Freq: \(Int(vm.baseFreqHz)) Hz")
                        Spacer()
                        Slider(value: $vm.baseFreqHz, in: 200...3000, step: 50)
                            .frame(width: 180)
                    }

                    Button {
                        vm.encodeAndDecode()
                    } label: {
                        HStack {
                            if vm.isRunning {
                                ProgressView()
                                    .padding(.trailing, 4)
                            }
                            Text("Encode + Decode")
                        }
                    }
                    .disabled(vm.isRunning || vm.message.isEmpty)
                }

                if let err = vm.errorText {
                    Section {
                        Text(err)
                            .foregroundStyle(.red)
                    }
                }

                if !vm.decodedMessages.isEmpty {
                    Section("Decoded Messages") {
                        ForEach(vm.decodedMessages, id: \.self) { msg in
                            VStack(alignment: .leading, spacing: 4) {
                                Text(msg.rawText)
                                    .font(.system(.body, design: .monospaced))
                                Text("SNR: \(msg.snr) dB  |  \(String(format: "%.0f", msg.freqHz)) Hz  |  Score: \(msg.score)")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }

                if !vm.waterfallBins.isEmpty {
                    Section("Waterfall") {
                        WaterfallCanvas(
                            bins: vm.waterfallBins,
                            rows: vm.waterfallRows,
                            cols: vm.waterfallCols
                        )
                        .frame(height: 200)
                    }
                }
            }
            .navigationTitle("Round Trip")
        }
    }
}
