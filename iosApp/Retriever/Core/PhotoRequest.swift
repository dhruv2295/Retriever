import Photos
import os

/// Photos need not call back after cancellation, and may call back before returning the request ID.
@concurrent func awaitPhotoRequest<Value>(
    cancel: @escaping @Sendable (PHImageRequestID) -> Void,
    start: @Sendable (@escaping @Sendable (sending Value?) -> Void) -> PHImageRequestID
) async -> sending Value? {
    let request = PhotoRequest<Value>()
    return await withTaskCancellationHandler(
        operation: { @Sendable () async -> sending Value? in
            await withCheckedContinuation { done in
                guard request.install(done) else {
                    done.resume(returning: nil)
                    return
                }
                let id = start { request.finish($0) }
                if request.register(id) { cancel(id) }
            }
        },
        onCancel: {
            if let id = request.cancel() { cancel(id) }
        })
}

private final class PhotoRequest<Value>: Sendable {
    private struct State {
        var done: CheckedContinuation<Value?, Never>?
        var id: PHImageRequestID?
        var cancelled = false
    }
    private let state = OSAllocatedUnfairLock(initialState: State())

    func install(_ done: CheckedContinuation<Value?, Never>) -> Bool {
        state.withLock {
            guard !$0.cancelled else { return false }
            $0.done = done
            return true
        }
    }

    func register(_ id: PHImageRequestID) -> Bool {
        state.withLock {
            $0.id = id
            return $0.cancelled
        }
    }

    func finish(_ value: sending Value?) {
        let done = state.withLock { s in
            defer { s.done = nil }
            return s.done
        }
        done?.resume(returning: value)
    }

    func cancel() -> PHImageRequestID? {
        let (done, id) = state.withLock { s in
            s.cancelled = true
            defer {
                s.done = nil
                s.id = nil
            }
            return (s.done, s.id)
        }
        done?.resume(returning: nil)
        return id
    }
}
