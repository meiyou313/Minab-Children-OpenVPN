/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabBackup (v0.9.8):
 *   - Exports clean cross-platform .ovpn configs via VpnProfile.getExportConfigFile()
 *     (no Android management directives, compatible with Windows/macOS/iOS/Android).
 *   - NEVER includes usernames or passwords in ANY backup or share file.
 *   - Standardized dated filenames:
 *       Minab-Backup_<Scope>_<N>cfg_yyyy.MM.dd_HH.mm.zip
 *       Minab-Share_<Scope>_<N>cfg_yyyy.MM.dd_HH.mm.zip
 *       <Name>_yyyy.MM.dd_HH.mm.ovpn (for single-config sharing)
 *   - Embeds a readable Minab-Info.txt summary inside every Backup/Share ZIP.
 *   - Full/partial backup metadata (minab-backup.json) tracks scope, multi-group
 *     memberships, manual vs. auto groups, favorites, and (for full "all" backups)
 *     app settings — restoring partial backups merges favorites without wiping
 *     existing stars or settings.
 */

package de.blinkt.openvpn.minab;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import de.blinkt.openvpn.BuildConfig;
import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.Preferences;
import de.blinkt.openvpn.core.ProfileManager;

public final class MinabBackup {
    /** Name of the metadata entry inside a full/group/selected backup zip. */
    public static final String META_ENTRY = "minab-backup.json";
    /** Readable summary text entry included inside backup & share zips. */
    public static final String INFO_ENTRY = "Minab-Info.txt";

    public static final String SCOPE_ALL = "all";
    public static final String SCOPE_GROUP = "group";
    public static final String SCOPE_SELECTED = "selected";

    private static final int MAX_TOTAL_BYTES = 64 * 1024 * 1024;
    private static final int MAX_FILES = 500;
    private static final int MAX_ENTRY_BYTES = 2 * 1024 * 1024;

    public static final class BackupPreview {
        public boolean wasBackup;
        public int version;
        public String scope = SCOPE_ALL;
        public String scopeLabel = "All";
        public int profileCount;
        public String exportedAtFormatted = "";
        public String appVersion = "";
    }

    /** Result of a restore. wasBackup is false when the zip had no metadata. */
    public static final class RestoreResult {
        public boolean wasBackup;
        public int imported;
        public int skippedDuplicates;
        public int failed;
        public int profilesRestored;
        public String scope = SCOPE_ALL;
        public String scopeLabel = "All";
        public String exportedAtFormatted = "";
        public String error;
    }

    private MinabBackup() {
    }

    // --- Standardized Filenames ---

    public static String formatTimestampForFile(long millis) {
        return new SimpleDateFormat("yyyy.MM.dd_HH.mm", Locale.US).format(new Date(millis));
    }

    public static String formatTimestampReadable(long millis) {
        return new SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.US).format(new Date(millis));
    }

    public static String sanitizeScopePart(String raw, String fallback) {
        if (raw == null || raw.trim().isEmpty()) return fallback;
        // Keep ASCII letters, digits, dots, dashes; turn spaces/punctuation into clean tokens
        String clean = raw.trim().replaceAll("\\s+", "").replaceAll("[^A-Za-z0-9._-]", "");
        if (clean.isEmpty()) return fallback;
        if (clean.length() > 28) clean = clean.substring(0, 28);
        return clean;
    }

    public static String sanitizeBackupScopePart(String raw, String fallback) {
        if (raw == null || raw.trim().isEmpty()) return fallback;
        String clean = raw.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "");
        if (clean.isEmpty()) return fallback;
        if (clean.length() > 28) clean = clean.substring(0, 28);
        return clean;
    }

    public static String buildBackupFileName(String scopeType, String groupName, int count) {
        String scopePart;
        if (SCOPE_GROUP.equals(scopeType)) {
            scopePart = sanitizeBackupScopePart(groupName, "Group");
        } else if (SCOPE_SELECTED.equals(scopeType)) {
            scopePart = "Selected";
        } else {
            scopePart = "All";
        }
        String ts = formatTimestampForFile(System.currentTimeMillis());
        return "Minab-Backup_" + scopePart + "_" + count + "cfg_" + ts + ".zip";
    }

    public static String buildShareZipFileName(String scopeType, String groupName, int count) {
        String scopePart;
        if (SCOPE_GROUP.equals(scopeType)) {
            scopePart = sanitizeScopePart(groupName, "Group");
        } else if (SCOPE_SELECTED.equals(scopeType)) {
            scopePart = "Selected";
        } else {
            scopePart = "All";
        }
        String ts = formatTimestampForFile(System.currentTimeMillis());
        return "Minab-Share_" + scopePart + "_" + count + "cfg_" + ts + ".zip";
    }

    public static String buildSingleOvpnFileName(VpnProfile profile) {
        String base = sanitizeScopePart(profile != null ? profile.mName : null, "config");
        String ts = formatTimestampForFile(System.currentTimeMillis());
        return base + "_" + ts + ".ovpn";
    }

    // --- Writing ---

    public static int writeProfilesZip(Context context, List<VpnProfile> profiles,
                                       OutputStream out) throws Exception {
        return writeProfilesZip(context, profiles, out, SCOPE_SELECTED, null);
    }

    /** Plain cross-platform .ovpn zip + Minab-Info.txt (NO credentials, NO metadata JSON). */
    public static int writeProfilesZip(Context context, List<VpnProfile> profiles,
                                       OutputStream out, String scopeType, String scopeLabel)
            throws Exception {
        int count = 0;
        long now = System.currentTimeMillis();
        Set<String> usedEntryNames = new HashSet<>();
        List<VpnProfile> writtenProfiles = new ArrayList<>();
        ZipOutputStream zip = new ZipOutputStream(out);
        try {
            for (VpnProfile profile : profiles) {
                if (profile == null) continue;
                try {
                    String entryName = uniqueOvpnEntryName(profile.mName, usedEntryNames);
                    String ovpn = profile.getExportConfigFile();
                    zip.putNextEntry(new ZipEntry(entryName));
                    zip.write(ovpn.getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                    writtenProfiles.add(profile);
                    count++;
                } catch (Exception ignored) {
                }
            }
            zip.putNextEntry(new ZipEntry(INFO_ENTRY));
            zip.write(buildInfoText(
                    false, scopeType, scopeLabel, writtenProfiles, now
            ).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } finally {
            zip.close();
        }
        return count;
    }

    public static int writeFullBackup(Context context, List<VpnProfile> profiles,
                                      OutputStream out) throws Exception {
        return writeFullBackup(context, profiles, out, SCOPE_ALL, "All");
    }

    /**
     * Minab Backup: clean cross-platform .ovpn files + Minab-Info.txt + minab-backup.json
     * (NEVER includes usernames or passwords).
     */
    public static int writeFullBackup(Context context, List<VpnProfile> profiles,
                                      OutputStream out, String scopeType, String scopeLabel)
            throws Exception {
        int count = 0;
        long now = System.currentTimeMillis();
        Set<String> usedEntryNames = new HashSet<>();
        List<VpnProfile> writtenProfiles = new ArrayList<>();
        ZipOutputStream zip = new ZipOutputStream(out);
        try {
            for (VpnProfile profile : profiles) {
                if (profile == null) continue;
                try {
                    String entryName = uniqueOvpnEntryName(profile.mName, usedEntryNames);
                    String ovpn = profile.getExportConfigFile();
                    zip.putNextEntry(new ZipEntry(entryName));
                    zip.write(ovpn.getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                    writtenProfiles.add(profile);
                    count++;
                } catch (Exception ignored) {
                }
            }
            zip.putNextEntry(new ZipEntry(INFO_ENTRY));
            zip.write(buildInfoText(
                    true, scopeType, scopeLabel, writtenProfiles, now
            ).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry(META_ENTRY));
            zip.write(buildMetadata(
                    context, writtenProfiles, scopeType, scopeLabel, now
            ).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } finally {
            zip.close();
        }
        return count;
    }

    private static String uniqueOvpnEntryName(String rawName, Set<String> used) {
        String base = rawName == null ? "" : rawName.replaceAll("[^A-Za-z0-9._-]", "_");
        base = base.replaceAll("_+", "_");
        if (base.isEmpty() || base.equals("_")) base = "config";
        String candidate = base + ".ovpn";
        int idx = 2;
        while (used.contains(candidate.toLowerCase(Locale.US))) {
            candidate = base + "_" + idx + ".ovpn";
            idx++;
        }
        used.add(candidate.toLowerCase(Locale.US));
        return candidate;
    }

    private static String buildInfoText(
            boolean isBackup,
            String scopeType,
            String scopeLabel,
            List<VpnProfile> profiles,
            long timestampMs) {
        String resolvedScope = !TextUtils.isEmpty(scopeLabel) ? scopeLabel
                : (SCOPE_SELECTED.equals(scopeType) ? "Selected" : "All");
        StringBuilder sb = new StringBuilder();
        sb.append(isBackup ? "Minab OpenVPN — Backup Archive\n" : "Minab OpenVPN — Shared Configs\n");
        sb.append("========================================\n");
        sb.append("Date & Time : ").append(formatTimestampReadable(timestampMs)).append("\n");
        sb.append("App Version : ").append(BuildConfig.VERSION_NAME).append("\n");
        sb.append("Scope       : ").append(resolvedScope);
        if (!TextUtils.isEmpty(scopeType)) {
            sb.append(" (").append(scopeType).append(")");
        }
        sb.append("\n");
        sb.append("Configs     : ").append(profiles.size()).append("\n");
        sb.append("Security    : Usernames and passwords are NEVER included in this file.\n\n");
        sb.append("Included servers:\n");
        for (int i = 0; i < profiles.size(); i++) {
            VpnProfile p = profiles.get(i);
            sb.append(i + 1).append(". ")
                    .append(p.mName == null ? "config" : p.mName)
                    .append(" [").append(p.getGroupsDisplayText()).append("]\n");
        }
        return sb.toString();
    }

    private static String buildMetadata(
            Context context,
            List<VpnProfile> profiles,
            String scopeType,
            String scopeLabel,
            long timestampMs) throws Exception {
        String resolvedScopeType = TextUtils.isEmpty(scopeType) ? SCOPE_ALL : scopeType;
        String resolvedScopeLabel = !TextUtils.isEmpty(scopeLabel) ? scopeLabel
                : (SCOPE_SELECTED.equals(resolvedScopeType) ? "Selected" : "All");

        JSONObject meta = new JSONObject();
        meta.put("version", 2);
        meta.put("appVersion", BuildConfig.VERSION_NAME);
        meta.put("exportedAt", timestampMs);
        meta.put("exportedAtFormatted", formatTimestampReadable(timestampMs));
        meta.put("scope", resolvedScopeType);
        meta.put("scopeLabel", resolvedScopeLabel);
        meta.put("profileCount", profiles.size());

        Set<String> exportedUuids = new HashSet<>();
        Set<String> usedGroups = new LinkedHashSet<>();
        for (VpnProfile p : profiles) {
            exportedUuids.add(p.getUUIDString());
            usedGroups.addAll(p.getAllGroups());
        }

        JSONArray categories = new JSONArray();
        JSONArray manualCategories = new JSONArray();
        if (SCOPE_ALL.equals(resolvedScopeType)) {
            for (String c : CategoryStore.getCategories(context)) categories.put(c);
            for (String m : CategoryStore.getManualCategories(context)) manualCategories.put(m);
            JSONArray orderArr = new JSONArray();
            for (String o : CategoryStore.readOrder(context)) orderArr.put(o);
            meta.put("categoryOrder", orderArr);
        } else {
            for (String g : usedGroups) {
                categories.put(g);
                if (CategoryStore.isManualCategory(context, g)) {
                    manualCategories.put(g);
                }
            }
        }
        meta.put("categories", categories);
        meta.put("manualCategories", manualCategories);

        JSONArray favorites = new JSONArray();
        for (String uuid : MinabFavorites.getAll(context)) {
            if (SCOPE_ALL.equals(resolvedScopeType) || exportedUuids.contains(uuid)) {
                favorites.put(uuid);
            }
        }
        meta.put("favorites", favorites);

        if (SCOPE_ALL.equals(resolvedScopeType)) {
            JSONObject settings = new JSONObject();
            SharedPreferences minabPrefs =
                    context.getSharedPreferences("MinabPrefs", Context.MODE_PRIVATE);
            settings.put("sortMode", minabPrefs.getInt("minab_sort_mode_v2", MinabProfileBus.SORT_REAL));
            SharedPreferences defPrefs = Preferences.getDefaultSharedPreferences(context);
            settings.put("showSpeed", defPrefs.getBoolean("minab_show_speed_notification", true));
            settings.put("failover", defPrefs.getBoolean("minab_failover", true));
            JSONArray allowedApps = new JSONArray();
            for (String app : MinabAllowedApps.getStoredApps(context)) allowedApps.put(app);
            settings.put("allowedApps", allowedApps);
            settings.put("allowedAppsAreDisallowed",
                    MinabAllowedApps.getStoredAreDisallowed(context));
            meta.put("settings", settings);
        }

        JSONArray profs = new JSONArray();
        for (VpnProfile profile : profiles) {
            JSONObject p = new JSONObject();
            p.put("uuid", profile.getUUIDString());
            p.put("name", profile.mName == null ? "" : profile.mName);
            p.put("remoteHost", profile.getPrimaryRemoteHost());
            p.put("remotePort", profile.getPrimaryRemotePort());
            p.put("category", profile.getCategory());
            JSONArray allGroupsArr = new JSONArray();
            for (String g : profile.getAllGroups()) {
                allGroupsArr.put(g);
            }
            p.put("groups", allGroupsArr);
            // SECURITY: usernames & passwords are NEVER exported into backups.
            profs.put(p);
        }
        meta.put("profiles", profs);
        return meta.toString();
    }

    // --- Reading & Inspection ---

    /** True when the zip bytes contain a Minab backup metadata entry. */
    public static boolean hasMetadata(byte[] zipBytes) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (META_ENTRY.equals(entry.getName())) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Inspect backup metadata before restoring so the UI can show a confirmation preview. */
    public static BackupPreview inspectBackup(byte[] zipBytes) {
        BackupPreview preview = new BackupPreview();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (META_ENTRY.equals(entry.getName())) {
                    byte[] metaBytes = readCapped(zip, MAX_ENTRY_BYTES);
                    if (metaBytes == null) return preview;
                    JSONObject meta = new JSONObject(new String(metaBytes, StandardCharsets.UTF_8));
                    preview.wasBackup = true;
                    preview.version = meta.optInt("version", 1);
                    preview.scope = meta.optString("scope", SCOPE_ALL);
                    preview.scopeLabel = meta.optString("scopeLabel", "All");
                    JSONArray profs = meta.optJSONArray("profiles");
                    preview.profileCount = meta.optInt("profileCount", profs != null ? profs.length() : 0);
                    String formatted = meta.optString("exportedAtFormatted", "");
                    if (formatted.isEmpty() && meta.has("exportedAt")) {
                        formatted = formatTimestampReadable(meta.optLong("exportedAt", 0L));
                    }
                    preview.exportedAtFormatted = formatted;
                    preview.appVersion = meta.optString("appVersion", "");
                    return preview;
                }
            }
        } catch (Exception ignored) {
        }
        return preview;
    }

    /**
     * Restore a full or partial Minab backup.
     * Note: never overwrites credentials; partial backups merge favorites and leave global
     * settings untouched.
     */
    public static RestoreResult restoreBackup(Context context, byte[] zipBytes) {
        RestoreResult result = new RestoreResult();
        List<Entry> ovpnEntries = new ArrayList<>();
        byte[] metaBytes = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            int total = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (META_ENTRY.equals(name)) {
                    metaBytes = readCapped(zip, MAX_ENTRY_BYTES);
                    continue;
                }
                if (!isOvpnName(name)) continue;
                if (ovpnEntries.size() >= MAX_FILES) continue;
                byte[] bytes = readCapped(zip, MAX_ENTRY_BYTES);
                if (bytes == null) {
                    result.failed++;
                    continue;
                }
                total += bytes.length;
                if (total > MAX_TOTAL_BYTES) {
                    result.error = "backup too large";
                    return result;
                }
                ovpnEntries.add(new Entry(name, bytes));
            }
        } catch (Exception e) {
            result.error = e.getMessage();
            return result;
        }
        if (metaBytes == null) {
            result.wasBackup = false;
            return result;
        }
        result.wasBackup = true;

        BulkImportHelper.ImportResult importResult = new BulkImportHelper.ImportResult();
        BulkImportHelper.ImportOptions opts = new BulkImportHelper.ImportOptions();
        opts.deferSaveList = true;
        for (Entry e : ovpnEntries) {
            BulkImportHelper.importOne(context,
                    new ByteArrayInputStream(e.bytes), fileNameOf(e.name), opts, importResult);
        }
        ProfileManager.getInstance(context).saveProfileList(context);

        result.imported = importResult.imported;
        result.skippedDuplicates = importResult.skippedDuplicates;
        result.failed += importResult.failed;

        try {
            JSONObject meta = new JSONObject(new String(metaBytes, StandardCharsets.UTF_8));
            result.scope = meta.optString("scope", SCOPE_ALL);
            result.scopeLabel = meta.optString("scopeLabel", "All");
            result.exportedAtFormatted = meta.optString("exportedAtFormatted", "");
            if (result.exportedAtFormatted.isEmpty() && meta.has("exportedAt")) {
                result.exportedAtFormatted = formatTimestampReadable(meta.optLong("exportedAt", 0L));
            }
            applyMetadata(context, meta);
            result.profilesRestored = meta.optJSONArray("profiles") == null
                    ? 0 : meta.optJSONArray("profiles").length();
        } catch (Exception e) {
            result.error = e.getMessage();
        }
        return result;
    }

    private static void applyMetadata(Context context, JSONObject meta) throws Exception {
        ProfileManager pm = ProfileManager.getInstance(context);
        String scope = meta.optString("scope", SCOPE_ALL);
        boolean isFullScope = SCOPE_ALL.equals(scope);

        Map<String, VpnProfile> byKey = new HashMap<>();
        Map<String, VpnProfile> byHostPort = new HashMap<>();
        for (VpnProfile p : pm.getProfiles()) {
            byKey.put(keyOf(p), p);
            String host = p.getPrimaryRemoteHost();
            if (host != null) {
                byHostPort.put(host.trim().toLowerCase(Locale.US) + ":" + p.getPrimaryRemotePort(), p);
            }
        }

        JSONArray categories = meta.optJSONArray("categories");
        if (categories != null) {
            for (int i = 0; i < categories.length(); i++) {
                CategoryStore.addCategory(context, categories.optString(i));
            }
        }
        JSONArray manualCategories = meta.optJSONArray("manualCategories");
        if (manualCategories != null) {
            for (int i = 0; i < manualCategories.length(); i++) {
                CategoryStore.addManualCategory(context, manualCategories.optString(i));
            }
        }
        if (isFullScope) {
            JSONArray orderArr = meta.optJSONArray("categoryOrder");
            if (orderArr != null && orderArr.length() > 0) {
                List<String> order = new ArrayList<>();
                for (int i = 0; i < orderArr.length(); i++) {
                    String g = orderArr.optString(i, "");
                    if (!g.isEmpty()) order.add(g);
                }
                if (!order.isEmpty()) CategoryStore.saveOrder(context, order);
            }
        }

        JSONArray profs = meta.optJSONArray("profiles");
        Map<String, VpnProfile> byBackupUuid = new HashMap<>();
        if (profs != null) {
            for (int i = 0; i < profs.length(); i++) {
                JSONObject p = profs.getJSONObject(i);
                String uuid = p.optString("uuid", "");
                String name = p.optString("name", "");
                String host = p.optString("remoteHost", "");
                int port = p.optInt("remotePort", 0);
                VpnProfile live = null;
                if (!uuid.isEmpty()) {
                    try {
                        live = ProfileManager.getIfPresent(context, uuid);
                    } catch (Exception ignored) {
                    }
                }
                if (live == null) live = byKey.get(name + "|" + host + "|" + port);
                if (live == null && !host.isEmpty()) {
                    live = byHostPort.get(host.trim().toLowerCase(Locale.US) + ":" + port);
                }
                if (live == null) continue;
                if (!uuid.isEmpty()) byBackupUuid.put(uuid, live);

                JSONArray groupsArr = p.optJSONArray("groups");
                if (groupsArr != null && groupsArr.length() > 0) {
                    List<String> restoredGroups = new ArrayList<>();
                    for (int gIdx = 0; gIdx < groupsArr.length(); gIdx++) {
                        String gName = groupsArr.optString(gIdx, "");
                        if (!gName.isEmpty()) {
                            restoredGroups.add(gName);
                            CategoryStore.addCategory(context, gName);
                        }
                    }
                    if (!restoredGroups.isEmpty()) {
                        if (isFullScope) {
                            live.setAllGroups(restoredGroups);
                        } else {
                            for (String gName : restoredGroups) {
                                live.addToGroup(gName);
                            }
                        }
                    }
                } else {
                    String category = p.optString("category", "");
                    if (!category.isEmpty()) {
                        if (isFullScope) live.setCategory(category);
                        else live.addToGroup(category);
                        CategoryStore.addCategory(context, category);
                    }
                }
                ProfileManager.saveProfile(context, live);
            }
        }

        JSONArray favorites = meta.optJSONArray("favorites");
        if (favorites != null) {
            Set<String> mergedFavorites = isFullScope
                    ? new HashSet<>()
                    : new HashSet<>(MinabFavorites.getAll(context));
            for (int i = 0; i < favorites.length(); i++) {
                VpnProfile live = byBackupUuid.get(favorites.optString(i));
                if (live != null) mergedFavorites.add(live.getUUIDString());
            }
            MinabFavorites.setAll(context, mergedFavorites);
        }

        if (isFullScope) {
            JSONObject settings = meta.optJSONObject("settings");
            if (settings != null) {
                SharedPreferences minabPrefs =
                        context.getSharedPreferences("MinabPrefs", Context.MODE_PRIVATE);
                SharedPreferences.Editor minabEdit = minabPrefs.edit();
                if (settings.has("sortMode")) {
                    minabEdit.putInt("minab_sort_mode_v2",
                            settings.optInt("sortMode", MinabProfileBus.SORT_REAL));
                }
                minabEdit.apply();
                SharedPreferences defPrefs = Preferences.getDefaultSharedPreferences(context);
                SharedPreferences.Editor defEdit = defPrefs.edit();
                if (settings.has("showSpeed")) {
                    defEdit.putBoolean("minab_show_speed_notification",
                            settings.optBoolean("showSpeed", true));
                }
                if (settings.has("failover")) {
                    defEdit.putBoolean("minab_failover", settings.optBoolean("failover", true));
                }
                defEdit.apply();
                JSONArray allowedApps = settings.optJSONArray("allowedApps");
                if (allowedApps != null) {
                    Set<String> apps = new HashSet<>();
                    for (int i = 0; i < allowedApps.length(); i++) {
                        apps.add(allowedApps.optString(i));
                    }
                    boolean disallowed = settings.optBoolean("allowedAppsAreDisallowed", true);
                    MinabAllowedApps.saveChoice(context, apps, disallowed);
                    MinabAllowedApps.applyToAllProfiles(context, apps, disallowed);
                }
            }
        }
    }

    private static String keyOf(VpnProfile p) {
        String name = p.mName == null ? "" : p.mName;
        String host = p.getPrimaryRemoteHost();
        if (host == null) host = "";
        return name + "|" + host + "|" + p.getPrimaryRemotePort();
    }

    private static final class Entry {
        final String name;
        final byte[] bytes;

        Entry(String name, byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }

    private static byte[] readCapped(InputStream in, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0, n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) return null;
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static boolean isOvpnName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".ovpn") || lower.endsWith(".conf");
    }

    private static String fileNameOf(String path) {
        if (path == null) return null;
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
