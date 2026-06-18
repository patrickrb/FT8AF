import SwiftUI

struct ContentView: View {
    var body: some View {
        TabView {
            RoundTripView()
                .tabItem {
                    Label("Round Trip", systemImage: "waveform")
                }

            SlotClockView()
                .tabItem {
                    Label("Slot Clock", systemImage: "clock")
                }

            QsoSimView()
                .tabItem {
                    Label("QSO Sim", systemImage: "antenna.radiowaves.left.and.right")
                }
        }
    }
}
