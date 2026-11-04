# Release build and same-APK promotion

## Build a stable or Beta APK set

The signed Portal workflow uses the existing GitHub signing secrets and signing configuration. A manual run accepts `version_name`, `version_code`, and `release_channel`; push builds use the version and channel defaults declared in the workflow. Version names accept `major.minor.patch`, `-beta.N`, `-canary.N`, or `-rc.N`. Keep one globally increasing Android version-code sequence across stable and prerelease builds.

Before building, `verify_release_apks.py` reads public GitHub Releases metadata and requires the requested code to exceed every code with valid `verification.json`. Legacy releases without that asset are reported as unknown and skipped; releases with present but malformed, incompatible, or certificate-mismatched metadata fail closed. API or asset download errors fail closed. `--skip-remote-version-check` exists only for offline tests and must not be used for release builds.

## Promote the tested Beta binary

Build and distribute the Beta, install and verify it, then use the exact three APKs and their accompanying `verification.json`. Do not rebuild for stable. Run:

```sh
python3 scripts/promote_release.py --directory app/build/outputs/distribution
```

This is a local dry-run only. It checks the report identity, all three names, bytes and SHA-256 values, APK native ABIs, the established v2 signing certificate, and package/version/versionCode using Android build tools. Its output describes the intended GitHub release state (`prerelease=false`, `make_latest=true`) without making any remote change. `--execute` is deliberately rejected; there is no remote mutation implementation in this workflow.

To move a Beta user back to stable, publish a later stable build with a higher version code. The Android application ID and CI signing identity remain unchanged, preserving upgrade compatibility.
