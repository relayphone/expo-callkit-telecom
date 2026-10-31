require 'json'

package = JSON.parse(File.read(File.join(__dir__, '..', 'package.json')))

# The config plugin writes these properties; regenerate the iOS project after changing them.
properties_path = File.join(Pod::Config.instance.installation_root, 'Podfile.properties.json')
properties = File.exist?(properties_path) ? JSON.parse(File.read(properties_path)) : {}
media_adapter = properties.fetch('expo-callkit-telecom.mediaAdapter', 'webrtc')
unless %w[webrtc livekit none].include?(media_adapter)
  raise 'ExpoCallKitTelecom: expo-callkit-telecom.mediaAdapter must be webrtc, livekit or none.'
end
simulator = properties.fetch('expo-callkit-telecom.simulator', 'false') == 'true'

conditions = []
conditions << 'EXPO_CALLKIT_TELECOM_WEBRTC' if media_adapter != 'none'
conditions << 'EXPO_CALLKIT_TELECOM_SIMULATOR' if simulator

Pod::Spec.new do |s|
  s.name           = 'ExpoCallKitTelecom'
  s.version        = package['version']
  s.summary        = package['description']
  s.description    = package['description']
  s.license        = package['license']
  s.author         = package['author']
  s.homepage       = package['homepage']
  s.platforms      = {
    :ios => '26.0'
  }
  s.swift_version  = '6.0'
  s.source         = { git: package['repository']['url'] }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'
  # The app's Podfile names the SDK's source; the config plugin adds it.
  s.dependency 'IosVoipSDK/Core'
  s.dependency 'IosVoipSDK/LiveCommunicationKit'
  s.dependency 'IosVoipSDK/PushKit'
  s.dependency 'IosVoipSDK/Simulator' if simulator
  # No WebRTC pod dependency: Swift compiles against the declarations in WebRTCHeaders/, and the
  # classes resolve when the app links whichever WebRTC build it ships. See WebRTCHeaders/README.md.

  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'SWIFT_INCLUDE_PATHS' => '$(PODS_TARGET_SRCROOT)/WebRTCHeaders',
    'SWIFT_ACTIVE_COMPILATION_CONDITIONS' => "$(inherited) #{conditions.join(' ')}".strip,
    'SWIFT_UPCOMING_FEATURE_EXISTENTIAL_ANY' => 'YES',
    'SWIFT_UPCOMING_FEATURE_INTERNAL_IMPORTS_BY_DEFAULT' => 'YES',
    'SWIFT_UPCOMING_FEATURE_MEMBER_IMPORT_VISIBILITY' => 'YES',
  }

  s.source_files = '*.swift'
  s.preserve_paths = 'WebRTCHeaders/**'
end
