# v2 releases

v2 is installed from a GitHub release asset, not from this fork on npm. Prerelease
versions such as `2.0.0-alpha.3` can change APIs.

## Cut a release

Update `package.json` and add the version's dated section to `CHANGELOG.md`. Merge
the reviewed change to `v2` after the `release-controls` check passes. It builds
and packs on GitHub-hosted Linux, checks the full file list and checksum, and
retains both in the run summary and `v2-release-controls` artifact.

Create and push an annotated tag on the merge commit:

```sh
git fetch https://github.com/relayphone/expo-callkit-telecom.git v2
git tag -a v2.0.0-alpha.3 FETCH_HEAD -m '2.0.0-alpha.3'
git push https://github.com/relayphone/expo-callkit-telecom.git v2.0.0-alpha.3
```

The tag must match `v` plus the package version, and its commit must be contained
in `v2`. The `v2 release` workflow rebuilds from that tagged commit, runs the
TypeScript and host tests, checks the packed files and writes `SHA256SUMS`.
It attests the tarball and checksum file, verifies both against the workflow,
tag and source commit, and only then creates the GitHub release. Versions with
a prerelease part are marked as prereleases. The release includes the tarball,
`SHA256SUMS` and `attestation.sigstore.json`; its notes contain the version's
changelog, source commit, CI run and install URL. No local archive is published.

## Verify a download

Download all three assets from the same release:

```sh
gh release download v2.0.0-alpha.3 -R relayphone/expo-callkit-telecom \
  -p 'expo-callkit-telecom-2.0.0-alpha.3.tgz' -p SHA256SUMS -p attestation.sigstore.json
sha256sum -c SHA256SUMS
```

On macOS, use `shasum -a 256 -c SHA256SUMS`. Independently check the annotated
tag's source commit against the reviewed merge commit. Use that full commit SHA
in place of `SOURCE_COMMIT` for each [attestation verification](https://cli.github.com/manual/gh_attestation_verify):

```sh
gh attestation verify expo-callkit-telecom-2.0.0-alpha.3.tgz \
  --repo relayphone/expo-callkit-telecom \
  --signer-workflow relayphone/expo-callkit-telecom/.github/workflows/v2-release.yml \
  --source-ref refs/tags/v2.0.0-alpha.3 --source-digest SOURCE_COMMIT \
  --deny-self-hosted-runners --bundle attestation.sigstore.json
gh attestation verify SHA256SUMS \
  --repo relayphone/expo-callkit-telecom \
  --signer-workflow relayphone/expo-callkit-telecom/.github/workflows/v2-release.yml \
  --source-ref refs/tags/v2.0.0-alpha.3 --source-digest SOURCE_COMMIT \
  --deny-self-hosted-runners --bundle attestation.sigstore.json
```

After verification, install via the release URL in `package.json`:

```json
"expo-callkit-telecom": "https://github.com/relayphone/expo-callkit-telecom/releases/download/v2.0.0-alpha.3/expo-callkit-telecom-2.0.0-alpha.3.tgz"
```
