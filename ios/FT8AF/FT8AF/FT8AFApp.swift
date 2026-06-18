import SwiftUI

@main
struct FT8AFApp: App {
    @State private var appState = AppState()
    @State private var engine = LiveEngine()

    var body: some Scene {
        WindowGroup {
            AppTabView()
                .environment(appState)
                .environment(engine)
                .task {
                    // Load persisted QSO log before starting the engine.
                    appState.logbook.records = QsoLogStore.load()
                    await engine.start(appState: appState)
                }
        }
    }
}
