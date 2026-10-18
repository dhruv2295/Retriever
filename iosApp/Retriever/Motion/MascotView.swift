import ImageIO
import RetrieverCore
import SwiftUI
import UIKit

/// The golden retriever, animated. Give it a width (160 in most places, 260 on the welcome); its height follows the
/// art. Decorative: screens carry the state in text, so it is hidden from VoiceOver.
///
/// Frames are animated WebPs (Motion/Frames, shared with Android) played by ImageIO, which decodes them as they show;
/// `MascotDirector` (shared Kotlin) picks each next clip. It shows a still pose instead with Reduce Motion, off screen,
/// outside the active scene, in Low Power Mode, or when the phone is hot.
struct MascotView: View {
    let mood: MascotMood
    /// false keeps the walk going (setup); true settles into a walk-and-rest cadence after two minutes (Home, Status).
    var settles = true

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @State private var onScreen = false

    var body: some View {
        MascotPlayerView(mood: mood, settles: settles, animate: onScreen && !reduceMotion && scenePhase == .active)
            .aspectRatio(MascotFrames.size(MascotDirector.shared.still(mood: mood)), contentMode: .fit)
            .onAppear { onScreen = true }
            .onDisappear { onScreen = false }
            .accessibilityHidden(true)
    }
}

private struct MascotPlayerView: UIViewRepresentable {
    let mood: MascotMood
    let settles: Bool
    let animate: Bool

    func makeUIView(context: Context) -> UIImageView {
        let view = context.coordinator.view
        view.contentMode = .scaleAspectFit
        view.setContentHuggingPriority(.defaultLow, for: .horizontal)
        view.setContentHuggingPriority(.defaultLow, for: .vertical)
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        view.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
        return view
    }

    func updateUIView(_ view: UIImageView, context: Context) {
        context.coordinator.update(mood: mood, animate: animate)
    }

    static func dismantleUIView(_ view: UIImageView, coordinator: MascotPlayer) { coordinator.update(mood: coordinator.mood, animate: false) }

    func makeCoordinator() -> MascotPlayer { MascotPlayer(mood: mood, settles: settles) }
}

/// Runs the director's steps on one image view. Main actor only; ImageIO calls its frame block on the main queue.
@MainActor final class MascotPlayer {
    let view = UIImageView()
    private(set) var mood: MascotMood
    private var animate = false
    private var task: Task<Void, Never>?
    private var last: MascotStep?
    private let appeared = Date()
    private var waiter: (id: Int, resume: CheckedContinuation<Void, Never>)?
    private var waits = 0
    private var endLoop = false
    /// Bumped whenever motion starts or stops, so an ImageIO animation from an earlier run stops at its next frame
    /// instead of drawing beside the new one (hidden and shown again within a frame).
    private var generation = 0

    private let settles: Bool

    init(mood: MascotMood, settles: Bool) {
        self.mood = mood
        self.settles = settles
        view.image = MascotFrames.still(MascotDirector.shared.still(mood: mood))
    }

    func update(mood: MascotMood, animate: Bool) {
        let moodChanged = mood != self.mood
        self.mood = mood
        if animate != self.animate {
            self.animate = animate
            generation += 1
            task?.cancel()
            resume()
            task = animate ? Task { await run() } : nil
        }
        if !animate { view.image = MascotFrames.still(MascotDirector.shared.still(mood: mood)) }
        if moodChanged { resume() }
    }

    private func run() async {
        let gen = generation
        while !Task.isCancelled {
            let now = mood
            guard Self.motionAllowed else {
                view.image = MascotFrames.still(MascotDirector.shared.still(mood: now))
                await wait(seconds: 10)
                continue
            }
            let step = MascotDirector.shared.next(mood: now, last: last, onScreen: Date().timeIntervalSince(appeared), settles: settles)
            #if DEBUG
                NSLog("Mascot %@: %@", now.name, step.description)
            #endif
            switch step.kind {
            case .hold:
                view.image = MascotFrames.still(step.clip)
                await wait(seconds: step.seconds?.doubleValue)
            case .once:
                await play(step.clip, loops: false, gen: gen)
            default:
                // Ask half a loop early: the request lands inside the last wanted loop, which then finishes.
                let end = Date().addingTimeInterval((step.seconds?.doubleValue ?? 0) - step.clip.seconds / 2)
                endLoop = false
                // A child Task inherits this actor, so the Kotlin clip never crosses isolation (async let would send it).
                let clip = step.clip
                let played = Task { await play(clip, loops: true, gen: gen) }
                while !endLoop, !Task.isCancelled, mood == now, Self.motionAllowed, Date() < end {
                    await wait(seconds: min(10, max(0, end.timeIntervalSinceNow)))
                }
                endLoop = true
                await played.value
            }
            // Only a finished step counts: one cut short (scrolled away, app left) plays again from its start.
            if !Task.isCancelled { last = step }
        }
    }

    /// Plays the clip from its first frame; a loop runs until `endLoop`, then finishes the loop it is in.
    private func play(_ clip: MascotClip, loops: Bool, gen: Int) async {
        guard let url = MascotFrames.url(clip.file) else { return }
        let count = MascotFrames.frameCount(clip)
        let delay = clip.seconds / Double(count)
        // ImageIO's frames live in a buffer it reuses and clears once the animation stops, so the first and the
        // held last frame are decoded here, on our own: no blank frame between clips.
        let source = CGImageSourceCreateWithURL(url as CFURL, nil)
        func own(_ index: Int) -> UIImage? {
            source.flatMap { CGImageSourceCreateImageAtIndex($0, index, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary) }
                .map { UIImage(cgImage: $0) }
        }
        if let first = own(0) { view.image = first }
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            var finished = false
            var shown = false
            // Loops keep the file's own count (forever); ImageIO reads 0 here as "no loops". One-shots play once.
            let options = (loops ? [:] : [kCGImageAnimationLoopCount: 1]) as CFDictionary
            let status = CGAnimateImageAtURLWithBlock(url as CFURL, options) { [weak self] index, image, stop in
                guard let self, !finished else {
                    stop.pointee = true
                    return
                }
                if self.generation != gen {
                    finished = true
                    stop.pointee = true
                    done.resume()
                    return
                }
                if shown || index != 0 { self.view.image = UIImage(cgImage: image) }
                shown = true
                if index == count - 1, !loops || self.endLoop {
                    finished = true
                    stop.pointee = true
                    if let last = own(index) { self.view.image = last }
                    // Hold the last frame for its own delay before the next clip's first frame.
                    DispatchQueue.main.asyncAfter(deadline: .now() + delay) { done.resume() }
                }
            }
            if status != noErr {
                finished = true
                done.resume()
            }
        }
    }

    private func wait(seconds: Double?) async {
        waits += 1
        let id = waits
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            waiter = (id, c)
            if let seconds {
                Task { [weak self] in
                    try? await Task.sleep(for: .seconds(seconds))
                    self?.resume(id)
                }
            }
        }
    }

    private func resume(_ id: Int? = nil) {
        guard let w = waiter, id == nil || w.id == id else { return }
        waiter = nil
        w.resume.resume()
    }

    static var motionAllowed: Bool {
        let info = ProcessInfo.processInfo
        return !info.isLowPowerModeEnabled && info.thermalState.rawValue < ProcessInfo.ThermalState.serious.rawValue
    }
}

/// The shared frames in the app bundle, with stills and frame counts read once.
@MainActor enum MascotFrames {
    private static var stills: [String: UIImage] = [:]
    private static var counts: [String: Int] = [:]

    static func url(_ file: String) -> URL? {
        let name = (file as NSString).deletingPathExtension
        return Bundle.main.url(forResource: name, withExtension: "webp")
            ?? Bundle.main.url(forResource: name, withExtension: "webp", subdirectory: "Motion/Frames")
    }

    static func still(_ clip: MascotClip) -> UIImage? {
        if let image = stills[clip.still] { return image }
        guard let url = url(clip.still), let image = UIImage(contentsOfFile: url.path) else { return nil }
        stills[clip.still] = image
        return image
    }

    static func size(_ clip: MascotClip) -> CGSize { still(clip)?.size ?? CGSize(width: 480, height: 360) }

    static func frameCount(_ clip: MascotClip) -> Int {
        if let n = counts[clip.file] { return n }
        let n = url(clip.file).flatMap { CGImageSourceCreateWithURL($0 as CFURL, nil) }.map(CGImageSourceGetCount) ?? 1
        counts[clip.file] = n
        return n
    }
}
