import SwiftUI

@main
struct FT8AFApp: App {
    @State private var appState = AppState()
    @State private var engine = LiveEngine()
    @State private var showSplash = true

    var body: some Scene {
        WindowGroup {
            ZStack {
                AppTabView()
                    .environment(appState)
                    .opacity(showSplash ? 0 : 1)

                if showSplash {
                    SplashScreen {
                        withAnimation(.easeInOut(duration: 0.4)) {
                            showSplash = false
                        }
                    }
                }
            }
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
