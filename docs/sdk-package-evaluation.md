# Evaluate the fork with the released SDK

The SDK is consumed from the [v0.5.0 release asset](https://github.com/relayphone/ios-voip-sdk/releases/tag/v0.5.0).
Each consuming workflow downloads the SDK tarball and verifies its SHA-256 against that release's
`SHA256SUMS` before installing the checked-in URL dependency and lockfile. The package evaluation
workflow also installs the fork tarball together with the SDK URL in a separate consumer. The root package
re-exports the SDK's canonical API, with the SDK providing iOS and this package providing Android.

Use Node.js 26 and Bun 1.4. From this repository:

```sh
node tools/sdk-evaluation.ts download-sdk /tmp/sdk-package
node tools/sdk-evaluation.ts install-sdk /tmp/sdk-package "$PWD"
npm run build
node tools/sdk-evaluation.ts install-sdk /tmp/sdk-package example/client
node tools/sdk-evaluation.ts check-consumer /tmp/sdk-package "$PWD" /tmp/package-consumer
```

Both temporary output directories must be new or empty as appropriate. Keep them outside the
source checkout and delete them after evaluation. No SDK checkout or SDK prepack build is needed.
The download contains the release tarball and its `SHA256SUMS`; each consumer rechecks the checksum.

Each workflow run downloads the SDK once and reuses the verified release files across its jobs.
The installer runs `bun install --frozen-lockfile` with the public URL dependency, leaving the
manifest and lockfile unchanged, and checks the SDK lock integrity against the verified archive.
The installed-journey workflow explicitly uses `temporary-fork` mode after adding its local fork
tarball; `bun install --no-save` preserves that fixture's checked-in lock and the SDK URL/integrity.
The isolated npm consumer also uses the URL, checks that its
installed archive integrity matches the verified release bytes, and requires a single SDK installation.

To install in an app, first pack the chosen fork checkout with `npm pack`. Install that tarball
together with the SDK asset, using the actual fork tarball filename:

```sh
npm install /path/to/expo-callkit-telecom-2.0.0-alpha.7.tgz https://github.com/relayphone/ios-voip-sdk/releases/download/v0.5.0/relayphone-ios-voip-sdk-0.5.0.tgz
npx expo install expo-notifications expo-build-properties
```

The fork's inherited version still does not identify a published v2 release.
Build the checkout before packing it (`npm run build`); `npm pack` does not build
the fork. Use Expo 57 or later. Configure one fork plugin entry, commit native
customizations, and run `npx expo prebuild --clean` when replacing the old native
provider. Reapply intentional native customizations and rebuild the application.
See [the regeneration contract](./canonical-contract.md#plugin-composition-and-native-project-upgrades).

The package allowlist contains the runtime/declaration entries, composed plugin,
Android library sources and required metadata. README, changelog and the existing license accompany
the archive. The duplicate iOS provider, instrumentation, tools, site, example
client/server, tests and research directories are excluded. Only the SDK asset
provides Apple's native sources. Inspect a real archive with:

```sh
mkdir -p /tmp/fork-package
npm pack --pack-destination /tmp/fork-package
tar -tzf /tmp/fork-package/expo-callkit-telecom-0.5.0.tgz
```

The consumer resolves both installed package entries, invokes both supported config plugin entries,
checks their native module registration metadata, requires a single installed SDK version, and
compiles a TypeScript consumer against the public root, `types` and SDK `plugin` exports. A runtime
fixture then loads both installed public roots with fake native and React boundaries. It verifies
shared SDK function identity, raw IDs, sanitized canonical errors, Bluetooth/other route updates,
and Android's absent preview/Focus state. Route and untyped-error fault controls must fail this
fixture. It uses
pinned Expo 57, React 19.2.3 and React Native 0.86.3 dependencies. Host checks do not execute React
Native modules in Node. CI also builds a thin iOS consumer from both installed tarballs.
The SDK registers the sole Apple provider; the fork registers Android only.
The fresh consumer job also prebuilds Android from the installed fork tarball;
the iOS consumer prebuilds and builds the same composition. Both start with a
new consumer directory and the released SDK URL, without a link to checkout
sources. CI validates the real `npm pack --json` file list and records it in the
job summary before installing the archive. The example uses the same composed
provider and canonical interface.

Keep temporary consumer tarballs outside publishable manifests. The fork declares the SDK's
release URL directly; it does not rely on an unpublished registry version or a local override.
