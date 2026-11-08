# Evaluate the SDK package before its npm release

The SDK's iOS Expo package is not published yet. The package evaluation workflow packs a full
`relayphone/ios-voip-sdk` checkout at the revision in `tools/sdk-evaluation.ts`, then installs that
tarball together with this package's tarball in a separate consumer directory. The root package
continues to expose its current API; this check prepares dependency installation without changing
the native interface or composing the packages yet.

Use Node.js 26 and a clean, full SDK checkout at the pinned revision. From this repository:

```sh
bun install --frozen-lockfile
npm run build
node tools/sdk-evaluation.ts pack-sdk /path/to/ios-voip-sdk /tmp/sdk-package
node tools/sdk-evaluation.ts check-consumer /tmp/sdk-package "$PWD" /tmp/package-consumer
```

Both temporary output directories must be new or empty as appropriate. Keep them outside either
source checkout, and delete them after evaluation. Packing runs the SDK's normal `npm ci` and
`npm pack`, including its `prepack` build and Swift source synchronization. Copying only `expo/`
would omit the SDK sources required by that hook. A tarball manifest records the exact revision,
version and SHA-512 integrity; the consumer verifies it before installation.

The workflow creates one SDK tarball and downloads the same artifact for its consumer check.
The consumer resolves both installed package entries, invokes both supported config plugin entries,
checks their native module registration metadata, requires a single installed SDK version, and
compiles a TypeScript consumer against the public root and `types` exports. It uses pinned Expo 57,
React 19.2.3 and React Native 0.86.3 dependencies. It does not execute React Native modules in Node
or build native projects. Native behavior remains covered by the platform tests.

These are evaluation tarballs, not releases. The SDK's release and license policy must be resolved
before publishing a dependency on it; do not commit temporary tarballs or local file dependencies.
