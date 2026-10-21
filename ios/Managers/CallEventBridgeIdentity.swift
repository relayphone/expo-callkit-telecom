import Foundation

/// Synchronous ownership fencing for asynchronous Expo lifecycle callbacks.
final class CallEventBridgeIdentity: @unchecked Sendable {
  private static let allocationLock = NSLock()
  private static var nextOrder: UInt64 = 0
  let order: UInt64
  private let lock = NSLock()
  private var alive = true

  init() {
    Self.allocationLock.lock()
    Self.nextOrder += 1; order = Self.nextOrder
    Self.allocationLock.unlock()
  }
  var isAlive: Bool { lock.lock(); defer { lock.unlock() }; return alive }
  func close() { lock.lock(); alive = false; lock.unlock() }
}
