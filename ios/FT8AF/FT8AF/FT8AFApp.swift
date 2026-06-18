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
                    // Wire engine into AppState so every view can reach it.
                    appState.engine = engine
                    // Load persisted settings and QSO log before starting the engine.
                    SettingsPersistence.load(into: appState.settings)
                    appState.logbook.records = QsoLogStore.load()
                    await engine.start(appState: appState)
                }
        }
    }
}
