# Privacy Policy — Minab OpenVPN

**Minab OpenVPN** (`com.minab.children`) is a free, open-source Android OpenVPN client based on **ics-openvpn** (GPLv2). Protecting user privacy is a core design principle: Minab collects **zero telemetry, zero analytics, zero advertisements, and zero user profiles** on any developer-controlled server.

## 1. Zero Telemetry & Local-Only Storage
- **VPN Profiles & Data**: Imported `.ovpn` profiles, group memberships (`[Group1 · Group2]`), favorites, Ping/Real test results, and traffic usage history are stored strictly locally on your device.
- **Usernames & Passwords**: Saved shared credentials (`SharedCredentials`) are encrypted locally using Android Keystore (`EncryptedSharedPreferences`) whenever supported by the device.
- **Zero-Credential Backups & Shares**: Exported `.ovpn`, `.zip`, `Minab-Info.txt`, and `minab-backup.json` files **never** include usernames or passwords.
- **Strictly Local Diagnostics Center**: Crash traces, breadcrumbs, app logs, and OpenVPN connection logs stay 100% on your device inside `Settings → Help & Diagnostics → Diagnostics & Debug Logs`. Nothing is ever uploaded automatically; logs leave your device only if you explicitly choose to download, copy, or share a `.txt` report.

## 2. User-Initiated Network Connections Only
Minab only communicates over the network for:
1. **Your VPN Tunnels**: Connecting to the OpenVPN servers you imported and selected.
2. **Real Test Reachability Checks**: When you run **Real Test**, Minab fetches only the target test URL you configured (default: `https://api.telegram.org/`) through the active candidate VPN tunnel.
3. **Optional Country Flag / GeoIP & Public IP Lookup**: When you import configs, tap **Re-detect IP**, or view your public IP on Home, Minab queries public GeoIP/IP endpoints (such as `get.geojs.io` or `ip-api.com`) over HTTPS. GeoIP detection can be skipped at any time during import.
4. **GitHub Update Check**: When launched or when you tap **Check for updates**, Minab checks the public GitHub Releases API for `meiyou313/Minab-Children-OpenVPN` (cached for 24 hours).

Please consult the respective privacy policies of your VPN server provider, target reachability endpoints, and GitHub when connecting through them.

## 3. Open-Source Rights (GPLv2)
This application and its components are distributed under open-source licenses (principally **GNU GPLv2**; see [`doc/LICENSE.txt`](doc/LICENSE.txt)), allowing anyone to audit, inspect, build, and use the software freely. Maintainers reserve the right to update or discontinue development at any time.
