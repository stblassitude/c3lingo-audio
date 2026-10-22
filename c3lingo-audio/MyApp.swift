import SwiftUI

@main struct MyApp: App {
    @StateObject private var player = Player()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(player)
                #if os(macOS)
                .frame(minWidth: 360, idealWidth: 480, minHeight: 480, idealHeight: 800)
                #endif
        }
        #if os(macOS)
        Settings {
            SettingsView()
        }
        #endif
    }
}
