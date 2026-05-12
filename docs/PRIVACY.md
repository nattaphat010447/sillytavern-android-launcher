# Privacy Policy

**Last updated: 2026-05-12**

## Summary

STANDROID does not collect, transmit, or store any personal data.
Everything stays on your device.

---

## Data collected

**None.** STANDROID does not:

- collect analytics or usage statistics
- track device identifiers, location, or user behaviour
- send crash reports to any external server
- use advertising SDKs or third-party tracking libraries

## Network access

STANDROID requires internet access for the following purposes only:

| Purpose | Destination |
|---------|-------------|
| Clone SillyTavern on first launch | `github.com` |
| Check for SillyTavern updates (optional) | `github.com` |
| Download npm package manager if not bundled | `registry.npmjs.org` |
| Download Termux native libraries during build (CI only) | `packages.termux.dev` |

No user data is included in any of these requests.

## Data stored on device

All data is stored locally under the app's private directory
(`/data/data/com.standroid.launcher/`) and is not accessible to other apps:

| Path | Contents |
|------|----------|
| `files/SillyTavern/` | SillyTavern source code and node_modules |
| `files/logs/standroid.log` | App and Node.js output (rotated at 2 MB) |
| `files/npm_pkg/` | Cached npm package manager |
| `cache/node_compile_cache/` | Node.js compiled bytecode cache |
| `shared_prefs/standroid_prefs.xml` | App settings (port, auto-update toggle) |

Uninstalling the app removes all of the above.

## SillyTavern

STANDROID launches [SillyTavern](https://github.com/SillyTavern/SillyTavern) locally
on your device. SillyTavern's own privacy practices apply once it is running.
Refer to the [SillyTavern documentation](https://docs.sillytavern.app/) for details.

## Contact

For questions about this policy, open an issue at
https://github.com/nattaphat010447/sillytavern-android-launcher/issues
