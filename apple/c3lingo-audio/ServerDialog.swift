import SwiftUI

/// Changes the server, for testing: one field, checked while typing.
///
/// The URL is shown without `https://`; an empty field means the default server. Once
/// the host looks complete, the page is fetched to check that it is a c3lingo frontend,
/// and Done stays disabled until it is. Done always reloads the page: a different server
/// starts over by itself, the same one calls `reload`.
struct ServerDialog: View {
    let reload: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var draft: String
    @State private var status = Status.valid

    enum Status: Equatable {
        case valid
        /// Still being typed, or waiting for typing to pause.
        case unchecked
        case checking
        case problem(String)
    }

    init(reload: @escaping () -> Void) {
        self.reload = reload
        let url = ServerSettings.url(from: ServerSettings.shared.customURLString)
        _draft = State(initialValue: url.flatMap { $0 == ServerSettings.defaultURL ? nil : ServerSettings.displayString(for: $0) } ?? "")
    }

    private var input: String { draft.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var canSave: Bool { input.isEmpty || status == .valid }

    var body: some View {
        NavigationView {
            Form {
                Section {
                    HStack {
                        TextField("Server URL", text: $draft, prompt: Text(verbatim: ServerSettings.displayString(for: ServerSettings.defaultURL)))
                            .urlInput()
                            .onSubmit(save)
                        if !draft.isEmpty {
                            Button {
                                draft = ""
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .foregroundStyle(.tertiary)
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel("Use the default server")
                        }
                    }
                } header: {
                    Text("Server URL")
                } footer: {
                    statusText
                }
                Section {
                    Link("Privacy Policy", destination: ServerSettings.privacyPolicyURL)
                }
            }
            .navigationTitle("Server")
            #if !os(macOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done", action: save)
                        .disabled(!canSave)
                }
            }
        }
        #if !os(macOS)
        .navigationViewStyle(.stack)
        #endif
        .task(id: draft) { await validate() }
        .compactSheet()
    }

    @ViewBuilder private var statusText: some View {
        switch status {
        case .checking:
            HStack(spacing: 6) {
                ProgressView()
                    .controlSize(.small)
                Text("Checking…")
            }
        case .problem(let message):
            Text(message)
                .foregroundStyle(.red)
        case .valid, .unchecked:
            EmptyView()
        }
    }

    private func validate() async {
        guard !input.isEmpty else {
            status = .valid
            return
        }
        status = .unchecked
        // Wait for typing to pause, so partial host names aren't looked up.
        try? await Task.sleep(nanoseconds: 700_000_000)
        guard !Task.isCancelled else { return }
        guard let url = ServerSettings.url(from: input) else {
            status = .problem(String(localized: "This isn't a valid web address."))
            return
        }
        guard ServerSettings.looksComplete(url) else { return }
        status = .checking
        let problem = await ServerCheck.problem(with: url)
        guard !Task.isCancelled else { return }
        status = problem.map(Status.problem) ?? .valid
    }

    private func save() {
        guard canSave else { return }
        let url = ServerSettings.url(from: input)
        let previous = ServerSettings.shared.serverURL
        ServerSettings.shared.customURLString = url == nil || url == ServerSettings.defaultURL ? "" : url!.absoluteString
        if ServerSettings.shared.serverURL == previous {
            reload()
        }
        dismiss()
    }
}

/// Checks that a server URL leads to a c3lingo frontend.
enum ServerCheck {
    /// Returns what's wrong, worded for the user, or nil if the page is a c3lingo frontend.
    static func problem(with url: URL) async -> String? {
        let request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 10)
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            if let status = (response as? HTTPURLResponse)?.statusCode, status >= 400 {
                return String(localized: "The server reported an error (HTTP \(status)).")
            }
            // Every frontend declares the native bridge version, see FrontendBridge.js.
            let page = String(decoding: data, as: UTF8.self)
            if page.range(of: #"<meta[^>]+name=["']?c3lingo-native-bridge"#, options: [.regularExpression, .caseInsensitive]) == nil {
                return String(localized: "This isn't a c3lingo audio site.")
            }
            return nil
        } catch let error as URLError where error.code == .cannotFindHost {
            return String(localized: "Can't find a server with this name.")
        } catch {
            return String(localized: "Can't connect to the server: \(error.localizedDescription)")
        }
    }
}

private extension View {
    /// The dialog has a single field; half the screen is enough where sheets can do that.
    @ViewBuilder func compactSheet() -> some View {
        if #available(iOS 16, macOS 13, *) {
            presentationDetents([.medium, .large])
        } else {
            self
        }
    }

    func urlInput() -> some View {
        #if os(macOS)
        self.disableAutocorrection(true)
        #else
        self
            .textContentType(.URL)
            .keyboardType(.URL)
            .textInputAutocapitalization(.never)
            .disableAutocorrection(true)
            .submitLabel(.done)
        #endif
    }
}
