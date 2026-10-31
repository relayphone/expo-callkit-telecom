import Foundation

/// A JSON value, which crosses the bridge to JavaScript and moves between actors.
enum JSON: Sendable, Equatable {
  case null
  case bool(Bool)
  case number(Double)
  case string(String)
  case array([JSON])
  case object([String: JSON])

  /// Converts a value that Expo decoded from JavaScript or `JSONSerialization` produced, or returns
  /// `nil` for a value that JSON can't hold.
  init?(_ value: Any?) {
    switch value {
    case nil, is NSNull:
      self = .null
    case let string as String:
      self = .string(string)
    case let number as NSNumber:
      // Booleans arrive as NSNumber too; only their Core Foundation type tells them apart.
      self =
        CFGetTypeID(number) == CFBooleanGetTypeID()
        ? .bool(number.boolValue) : .number(number.doubleValue)
    case let array as [Any]:
      var values: [JSON] = []
      for element in array {
        guard let value = JSON(element) else { return nil }
        values.append(value)
      }
      self = .array(values)
    case let object as [String: Any]:
      var values: [String: JSON] = [:]
      for (key, element) in object {
        guard let value = JSON(element) else { return nil }
        values[key] = value
      }
      self = .object(values)
    default:
      return nil
    }
  }

  /// The value as Foundation types, for Expo and `JSONSerialization`.
  var foundation: Any {
    switch self {
    case .null: NSNull()
    case .bool(let value): value
    case .number(let value): value
    case .string(let value): value
    case .array(let values): values.map(\.foundation)
    case .object(let values): values.mapValues(\.foundation)
    }
  }

  /// The object's members as Foundation types, or an empty dictionary for another value.
  var foundationObject: [String: Any] {
    guard case .object(let values) = self else { return [:] }
    return values.mapValues(\.foundation)
  }

  /// The member with the given key, when the value is an object that has one.
  subscript(key: String) -> JSON? {
    guard case .object(let values) = self else { return nil }
    return values[key]
  }

  var string: String? {
    if case .string(let value) = self { value } else { nil }
  }

  var bool: Bool? {
    if case .bool(let value) = self { value } else { nil }
  }

  var array: [JSON]? {
    if case .array(let values) = self { values } else { nil }
  }

  /// The value without the given member, when it is an object.
  func removing(_ key: String) -> JSON {
    guard case .object(var values) = self else { return self }
    values[key] = nil
    return .object(values)
  }

  /// The value encoded as JSON data.
  func data() throws -> Data {
    try JSONSerialization.data(withJSONObject: foundation, options: [.fragmentsAllowed])
  }

  /// An ISO 8601 date, as the TypeScript API writes dates.
  static func date(_ date: Date) -> JSON {
    .string(date.formatted(.iso8601))
  }
}
