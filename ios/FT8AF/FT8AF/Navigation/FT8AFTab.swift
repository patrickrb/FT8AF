import SwiftUI

enum FT8AFTab: String, CaseIterable, Identifiable {
    case decode
    case waterfall
    case logbook
    case map
    case pota
    case settings

    var id: String { rawValue }

    var label: String {
        switch self {
        case .decode:    return "Decode"
        case .waterfall: return "Waterfall"
        case .logbook:   return "Log"
        case .map:       return "Map"
        case .pota:      return "POTA"
        case .settings:  return "Settings"
        }
    }

    var icon: String {
        switch self {
        case .decode:    return "waveform"
        case .waterfall: return "chart.bar.fill"
        case .logbook:   return "book.closed.fill"
        case .map:       return "globe"
        case .pota:      return "tree.fill"
        case .settings:  return "gearshape.fill"
        }
    }
}
