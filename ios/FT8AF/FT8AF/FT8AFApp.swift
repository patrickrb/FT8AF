import SwiftUI

@main
struct FT8AFApp: App {
    @State private var appState = AppState()
    @State private var engine = LiveEngine()

    var body: some Scene {
        WindowGroup {
            AppTabView()
                .environment(appState)
                .task {
                    await engine.start(appState: appState)
                }
        }
    }
}
