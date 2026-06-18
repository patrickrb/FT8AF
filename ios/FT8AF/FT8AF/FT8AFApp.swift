import SwiftUI

@main
struct FT8AFApp: App {
    @State private var appState = AppState()

    var body: some Scene {
        WindowGroup {
            AppTabView()
                .environment(appState)
        }
    }
}
