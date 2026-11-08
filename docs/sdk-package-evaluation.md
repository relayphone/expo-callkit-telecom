# Evaluate the SDK package before its release

The SDK is currently consumed from a pinned archive. Each consuming workflow packs a full
`relayphone/ios-voip-sdk` checkout at the revision in `tools/sdk-evaluation.ts`, then installs that
archive before building its source checkouts. The package evaluation workflow also installs both
packages' tarballs in a separate consumer directory. The root package
re-exports the SDK's canonical API, with the SDK providing iOS and this package providing Android.

Use Node.js 26 and a clean, full SDK checkout at the pinned revision. From this repository:

```sh
node tools/sdk-evaluation.ts pack-sdk /path/to/ios-voip-sdk /tmp/sdk-package
node tools/sdk-evaluation.ts install-sdk /tmp/sdk-package "$PWD"
npm run build
node tools/sdk-evaluation.ts install-sdk /tmp/sdk-package example/client
node tools/sdk-evaluation.ts check-consumer /tmp/sdk-package "$PWD" /tmp/package-consumer
```

Both temporary output directories must be new or empty as appropriate. Keep them outside either
source checkout, and delete them after evaluation. Packing runs the SDK's normal `npm ci` and
`npm pack`, including its `prepack` build and Swift source synchronization. Copying only `expo/`
would omit the SDK sources required by that hook. A tarball manifest records the exact revision,
version and SHA-512 integrity; the consumer verifies it before installation.

Each workflow run creates one SDK tarball and reuses that archive in all its build jobs. The
installer verifies the revision and integrity, then temporarily resolves the SDK dependency and
override to the local archive before running `bun install --no-save`. Other dependency versions
are seeded by the existing lockfile. It restores the original package manifest even if installation
fails, and does not change the checked-in lockfile. No unpublished SDK registry lookup is needed.

The consumer resolves both installed package entries, invokes both supported config plugin entries,
checks their native module registration metadata, requires a single installed SDK version, and
compiles a TypeScript consumer against the public root, `types` and SDK `plugin` exports. A runtime
fixture then loads both installed public roots with fake native and React boundaries. It verifies
shared SDK function identity, raw IDs, sanitized canonical errors, Bluetooth/other route updates,
and Android's absent preview/Focus state. Route and untyped-error fault controls must fail this
fixture. It uses
pinned Expo 57, React 19.2.3 and React Native 0.86.3 dependencies. Host checks do not execute React
Native modules in Node. CI also builds a thin iOS consumer from both installed tarballs, using only
the SDK's Apple module and plugin; the legacy package is excluded from that fixture's autolinking.
The example uses the same composed provider and canonical interface.

Tagged SDK and fork releases will be distributed as GitHub release assets. The pinned archive
flow remains in place until the SDK release is available. Keep temporary tarballs and local file
dependencies outside publishable manifests.
