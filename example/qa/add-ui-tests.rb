#!/usr/bin/env ruby
# Adds the regression target only to the locally generated Expo Xcode project.
require 'xcodeproj'

project_path, libraries, api, container_index, app_info = ARGV
api ||= 'metadata'
abort 'push API must be metadata or legacy' unless %w[metadata legacy].include?(api)
abort 'usage: add-ui-tests.rb <generated .xcodeproj> <runtime libraries>' unless libraries && project_path
project = Xcodeproj::Project.open(project_path)
app = project.targets.find { |target| target.name == 'CallKitTel' }
abort 'CallKitTel app target missing' unless app
target = project.targets.find { |candidate| candidate.name == 'PushIsolationUITests' }
unless target
  target = project.new_target(:ui_test_bundle, 'PushIsolationUITests', :ios, '16.4')
  target.add_dependency(app)
  source = File.expand_path('PushIsolationUITests.swift', __dir__)
  target.source_build_phase.add_file_reference(project.main_group.new_file(source))
end
target.build_configurations.each do |configuration|
  configuration.build_settings.merge!(
    'PRODUCT_BUNDLE_IDENTIFIER' => 'com.example.expocallkittelecom.push-qa',
    'PRODUCT_NAME' => 'PushIsolationUITests',
    'GENERATE_INFOPLIST_FILE' => 'YES',
    'TEST_TARGET_NAME' => 'CallKitTel',
    'SWIFT_VERSION' => '5.0',
    'SWIFT_OBJC_BRIDGING_HEADER' => File.expand_path('PushIsolation-Bridging-Header.h', __dir__),
    'TARGETED_DEVICE_FAMILY' => '1'
  )
end
project.save

scheme_path = File.join(project_path, 'xcshareddata/xcschemes/CallKitTel.xcscheme')
scheme = Xcodeproj::XCScheme.new(scheme_path)
unless scheme.test_action.testables.any? { |reference|
  reference.xml_element.elements['BuildableReference']&.attributes&.[]('BlueprintIdentifier') == target.uuid
}
  scheme.add_test_target(target)
end
variables = scheme.test_action.environment_variables
variables.assign_variable(key: 'CKSIM_QA_RUNTIME_LIBRARIES', value: libraries, enabled: true)
variables.assign_variable(key: 'CKSIM_QA_PUSH_API', value: api, enabled: true)
variables.assign_variable(key: 'CKSIM_QA_CONTAINER_INDEX', value: container_index, enabled: true)
variables.assign_variable(key: 'CKSIM_QA_APP_INFO', value: app_info, enabled: true)
scheme.test_action.environment_variables = variables
scheme.test_action.should_use_launch_scheme_args_env = false
scheme.save_as(project_path, 'CallKitTel')
