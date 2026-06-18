import Foundation
import FT8DSP

@MainActor
@Observable
final class RoundTripViewModel {
    var message: String = "CQ K1ABC FN42"
    var baseFreqHz: Float = 1000
    var decodedMessages: [DecodedMessage] = []
    var waterfallBins: [UInt8] = []
    var waterfallRows: Int = 0
    var waterfallCols: Int = 0
    var hzPerCol: Float = 0
    var isRunning: Bool = false
    var errorText: String?

    func encodeAndDecode() {
        guard !isRunning else { return }
        isRunning = true
        errorText = nil
        decodedMessages = []
        waterfallBins = []

        let msg = message
        let freq = baseFreqHz

        Task.detached {
            guard let samples = FT8Encoder.generateFT8(msg, baseFreqHz: freq) else {
                await MainActor.run { [weak self] in
                    self?.errorText = "Encoding failed — check message format"
                    self?.isRunning = false
                }
                return
            }

            let decoder = FT8Decoder()
            decoder.feedSlot(samples)
            decoder.findSync()
            let decoded = decoder.decodeAll()
            let heatmap = decoder.waterfallHeatmap(maxRows: 100, maxCols: 200)

            await MainActor.run { [weak self] in
                self?.decodedMessages = decoded
                self?.waterfallBins = heatmap.bins
                self?.waterfallRows = heatmap.rows
                self?.waterfallCols = heatmap.cols
                self?.hzPerCol = heatmap.hzPerCol
                self?.isRunning = false
            }
        }
    }
}
