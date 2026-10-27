import SwiftUI

/// The frontend's help text, from the bridge's optional `help` message: what c3lingo is,
/// how to use the soundcheck, what the channels are, and whom to ask.
nonisolated struct Help: Equatable, Sendable {
    /// Text shown one after another in the same paragraph.
    struct Run: Equatable, Sendable {
        let text: String
        /// A label as the page shows it, e.g. **Soundcheck**.
        let strong: Bool
        /// Only `https` and `mailto` links, which open in the browser or mail app.
        let link: URL?
    }

    enum Block: Equatable, Sendable {
        case heading(String)
        case paragraph([Run])
        /// A bulleted list; each item is one paragraph.
        case list([[Run]])
    }

    /// The BCP 47 tag of the text's language, e.g. `de-DE`.
    let language: String?
    let title: String
    let blocks: [Block]

    /// Parses the content of a `help` message, leaving out block types and run fields it
    /// doesn't know.
    init?(json: Any?) {
        guard let json = json as? [String: Any],
              let title = json["title"] as? String, !title.isEmpty,
              let blocks = json["blocks"] as? [Any] else {
            return nil
        }
        func runs(_ json: Any?) -> [Run] {
            (json as? [Any] ?? []).compactMap { json in
                guard let json = json as? [String: Any], let text = json["text"] as? String, !text.isEmpty else { return nil }
                let link = (json["href"] as? String).flatMap(URL.init(string:))
                    .flatMap { ["https", "mailto"].contains($0.scheme?.lowercased()) ? $0 : nil }
                return Run(text: text, strong: json["strong"] as? Bool ?? false, link: link)
            }
        }
        self.blocks = blocks.compactMap { json in
            guard let json = json as? [String: Any] else { return nil }
            switch json["type"] as? String {
            case "heading":
                return (json["text"] as? String).flatMap { $0.isEmpty ? nil : .heading($0) }
            case "paragraph":
                let text = runs(json["text"])
                return text.isEmpty ? nil : .paragraph(text)
            case "list":
                let items = (json["items"] as? [Any] ?? []).map(runs).filter { !$0.isEmpty }
                return items.isEmpty ? nil : .list(items)
            default:
                return nil
            }
        }
        guard !self.blocks.isEmpty else { return nil }
        self.title = title
        language = (json["language"] as? String).flatMap { $0.isEmpty ? nil : $0 }
    }
}

/// Shows the help in a sheet, from the page's help button or by itself on the first launch.
struct HelpView: View {
    let help: Help

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    ForEach(help.blocks.indices, id: \.self) { index in
                        block(help.blocks[index])
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
            }
            .navigationTitle(help.title)
            #if !os(macOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 420, idealWidth: 480, minHeight: 480, idealHeight: 600)
        #else
        .navigationViewStyle(.stack)
        #endif
    }

    @ViewBuilder private func block(_ block: Help.Block) -> some View {
        switch block {
        case .heading(let text):
            Text(text)
                .font(.headline)
                .padding(.top, 8)
                .accessibilityAddTraits(.isHeader)
        case .paragraph(let runs):
            Text(Self.text(runs))
        case .list(let items):
            VStack(alignment: .leading, spacing: 6) {
                ForEach(items.indices, id: \.self) { index in
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(verbatim: "•")
                            .accessibilityHidden(true)
                        Text(Self.text(items[index]))
                    }
                }
            }
        }
    }

    private static func text(_ runs: [Help.Run]) -> AttributedString {
        runs.reduce(into: AttributedString()) { text, run in
            var part = AttributedString(run.text)
            if run.strong {
                part.inlinePresentationIntent = .stronglyEmphasized
            }
            part.link = run.link
            text += part
        }
    }
}
