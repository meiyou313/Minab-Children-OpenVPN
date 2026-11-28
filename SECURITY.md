# Security Policy

## Supported Versions

Active fixes and security patches target the latest **Minab OpenVPN** beta release line:

| Version | Supported | Notes |
| :--- | :---: | :--- |
| `0.9.8.x-beta` (latest) | Yes | Active maintenance & CI releases |
| `< 0.9.8` | No | Upgrade to the latest `0.9.8.x` pre-release |

## Security Guarantees in Minab OpenVPN
- **Zero Credentials in Exports**: `MinabBackup` strips authentication secrets cleanly — usernames and passwords are **never** written into `.ovpn`, `.zip`, `Minab-Info.txt`, or `minab-backup.json` exports or shares.
- **Local Credential Protection**: Optional shared credentials use Android Keystore (`EncryptedSharedPreferences`).
- **Permanent Release Signing Continuity**: Every official Minab APK from `v0.4` onward is signed by the same dedicated RSA-2048 `minab` keystore so Android verifies authenticity across upgrades.

## Reporting a Vulnerability
- **Minab-specific features or UI issues** (imports, backups, credential dialogs, Diagnostics Center, connection management): please open an issue on [`meiyou313/Minab-Children-OpenVPN`](https://github.com/meiyou313/Minab-Children-OpenVPN/issues) or avoid posting private server keys, certificates, or credentials publicly.
- **Upstream OpenVPN core or `ics-openvpn` vulnerabilities**: report directly to upstream `ics-openvpn` / OpenVPN security via **`security@openvpn.net`**.
