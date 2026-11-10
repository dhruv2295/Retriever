// Ported from Retriever's Android FaceAvatar.kt and PersonActions.kt (same project).

import RetrieverCore
import SwiftUI

/// A person's face in a circle, cut from their cover photo; their initial, or an empty circle, until it loads.
struct FaceAvatar: View {
    let cover: FaceCrop?
    let name: String?
    let side: CGFloat
    @State private var face: UIImage?
    @Environment(\.services) private var services
    @Environment(\.displayScale) private var scale

    var body: some View {
        ZStack {
            Circle().fill(.fill.tertiary)
            if let face {
                Image(uiImage: face).resizable().scaledToFill()
            } else if let initial = name?.first {
                Text(String(initial).uppercased()).font(.title2.bold()).foregroundStyle(.tint)
            } else {
                Image(systemName: "person.fill").font(.title2).foregroundStyle(.secondary)
            }
        }
        .frame(width: side, height: side)
        .clipShape(.circle)
        .accessibilityHidden(true)
        .task(id: cover) { face = await FaceCrops.load(cover, pixels: Thumbnails.bucket(side * scale), services: services) }
    }
}

@MainActor
enum FaceCrops {
    private static let cache: NSCache<NSString, UIImage> = {
        let c = NSCache<NSString, UIImage>()
        c.countLimit = 200
        return c
    }()
    /// The crop is this much wider than the face's box, so hair and chin show.
    nonisolated private static let margin: CGFloat = 1.6

    static func load(_ crop: FaceCrop?, pixels: CGFloat, services: AppServices?) async -> UIImage? {
        guard let crop, let extractor = services?.extractor else { return nil }
        let b = crop.box
        let box = CGRect(x: CGFloat(b.left), y: CGFloat(b.top), width: CGFloat(b.right - b.left), height: CGFloat(b.bottom - b.top))
        let uri = crop.uri
        let key = "\(uri)|\(box.debugDescription)|\(Int(pixels))" as NSString
        if let hit = cache.object(forKey: key) { return hit }
        guard let image = await FileExtractor.blocking({ cut(extractor, uri, box, pixels) }) else { return nil }
        cache.setObject(image, forKey: key)
        return image
    }

    /// The face with a margin around it, square; the photo is decoded only as large as `px` needs.
    nonisolated private static func cut(_ extractor: FileExtractor, _ uri: String, _ box: CGRect, _ px: CGFloat) -> UIImage? {
        let longSide = min(max(px * margin / max(box.width, box.height, 0.05), px), 2048)
        guard let photo = extractor.uprightImage(uri: uri, maxSide: Int32(longSide)) else { return nil }
        let (w, h) = (CGFloat(photo.width), CGFloat(photo.height))
        let side = min(max(max(box.width * w, box.height * h) * margin, 1), min(w, h)).rounded(.down)
        let x = min(max(box.midX * w - side / 2, 0), w - side)
        let y = min(max(box.midY * h - side / 2, 0), h - side)
        return photo.cropping(to: CGRect(x: x, y: y, width: side, height: side).integral).map { UIImage(cgImage: $0) }
    }
}

/// A person's toolbar actions: name them, or say another group is them too.
struct PersonActions: View {
    let person: Person?
    let others: [Person]
    let rename: (String) -> Void
    let sameAs: (Int64) -> Void
    @State private var renaming = false
    @State private var picking = false
    @State private var name = ""

    var body: some View {
        HStack {
            Button {
                name = person?.name ?? ""
                renaming = true
            } label: {
                Label(person?.name == nil ? Words.shared.ADD_A_NAME : "Rename", systemImage: "pencil")
            }
            if !others.isEmpty {
                Menu {
                    Button(Words.shared.SAME_PERSON_AS, systemImage: "person.2") { picking = true }
                } label: {
                    Label("More options", systemImage: "ellipsis")
                }
            }
        }
        .alert(Words.shared.personTitle(name: person?.name), isPresented: $renaming) {
            TextField(Words.shared.NAME, text: $name)
            Button(Words.shared.SAVE) { rename(name) }
            Button(Words.shared.CANCEL, role: .cancel) {}
        }
        .sheet(isPresented: $picking) { picker }
    }

    private var picker: some View {
        NavigationStack {
            List(others, id: \.id) { p in
                Button {
                    sameAs(p.id)
                    picking = false
                } label: {
                    HStack(spacing: 12) {
                        FaceAvatar(cover: p.cover, name: p.name, side: 40)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(Words.shared.personTitle(name: p.name))
                            Text(Words.shared.photos(n: Int64(p.photos))).font(.subheadline).foregroundStyle(.secondary)
                        }
                    }
                }
                .foregroundStyle(Color.primary)
            }
            .navigationTitle(Words.shared.SAME_PERSON_AS)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(Words.shared.CANCEL) { picking = false } } }
        }
        .presentationDetents([.medium, .large])
    }
}
