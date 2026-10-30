import SwiftUI

#if os(macOS)
/// The server settings on macOS. On iOS, iPadOS and visionOS they live in the
/// Settings app (see Settings.bundle).
struct SettingsView: View {
    @ObservedObject private var settings = ServerSettings.shared
    @State private var draft = ""

    private var parsedDraft: URL? { ServerSettings.url(from: draft) }

    var body: some View {
        Form {
            Section {
                TextField("Server URL", text: $draft, prompt: Text(ServerSettings.defaultURL.absoluteString))
                    .disableAutocorrection(true)
                    .onSubmit(apply)
            } footer: {
                VStack(alignment: .leading, spacing: 4) {
                    if !draft.isEmpty && parsedDraft == nil {
                        Text("This is not a valid http or https URL.")
                            .foregroundStyle(.red)
                    }
                    Text("Leave empty to use \(ServerSettings.defaultURL.absoluteString). Currently using \(settings.serverURL.absoluteString).")
                        .foregroundStyle(.secondary)
                }
                .font(.footnote)
            }
            HStack {
                Button("Use Default") {
                    draft = ""
                    apply()
                }
                .disabled(settings.isUsingDefault && draft.isEmpty)
                Spacer()
                Button("Apply", action: apply)
                    .keyboardShortcut(.defaultAction)
                    .disabled(!draft.isEmpty && parsedDraft == nil)
            }
            OpenSourceSection()
            Link("Privacy Policy", destination: ServerSettings.privacyPolicyURL)
        }
        .groupedFormStyle()
        .padding()
        .frame(width: 460)
        .onAppear { draft = settings.customURLString }
    }

    private func apply() {
        guard draft.isEmpty || parsedDraft != nil else { return }
        ServerSettings.shared.customURLString = parsedDraft?.absoluteString ?? ""
        draft = ServerSettings.shared.customURLString
    }
}

private extension View {
    @ViewBuilder func groupedFormStyle() -> some View {
        if #available(macOS 13, *) {
            formStyle(.grouped)
        } else {
            self
        }
    }
}

#Preview {
    SettingsView()
}
#endif
