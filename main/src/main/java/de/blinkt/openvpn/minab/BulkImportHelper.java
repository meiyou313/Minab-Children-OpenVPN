/*
 * Minab Children — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab Children contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * BulkImportHelper: non-UI core of bulk import. Parses .ovpn / .conf text with
 * ConfigParser, preserves sanitized original .ovpn config text for clean export,
 * detects duplicates BEFORE renaming, supports deferred batch ProfileManager saves,
 * and avoids blocking on GeoIP.
 */

package de.blinkt.openvpn.minab;

import android.content.Context;
import android.text.TextUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.ConfigParser;
import de.blinkt.openvpn.core.ProfileManager;

public final class BulkImportHelper {
    public static final int MAX_FILES_PER_IMPORT = 500;
    public static final int MAX_ENTRY_BYTES = 2 * 1024 * 1024; // = VpnProfile.MAX_EMBED_FILE_SIZE

    public static final class ImportResult {
        public int imported;
        public int skippedDuplicates;
        public int failed;
        public final List<String> failedNames = new ArrayList<>();
        public final List<VpnProfile> importedProfiles = new ArrayList<>();

        public String summary() {
            return "Imported: " + imported
                    + ", duplicates skipped: " + skippedDuplicates
                    + ", failed: " + failed;
        }
    }

    public static final class ImportOptions {
        /** When non-null, apply to every imported profile that uses auth-user-pass. */
        public String username;
        public String password;
        public boolean applyCredentialsToAll;
        /** Category to assign to every imported profile (null = leave default). */
        public String category;
        /** When true, caller invokes ProfileManager.saveProfileList(context) once at batch end. */
        public boolean deferSaveList;
    }

    public interface ProgressListener {
        void onProgress(int processed);
    }

    private BulkImportHelper() {
    }

    /** Count .ovpn/.conf entries inside a zip byte array so progress can be determinate. */
    public static int countZipOvpnEntries(byte[] zipBytes) {
        if (zipBytes == null) return 0;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && isOvpnName(entry.getName())) {
                    count++;
                }
            }
        } catch (Exception ignored) {
        }
        return count;
    }

    /**
     * Import one .ovpn/.conf document. Checks duplicate endpoint BEFORE renaming,
     * preserves sanitized original config text, and persists via ProfileManager.
     */
    public static VpnProfile importOne(Context context, InputStream in,
                                       String displayName, ImportOptions options,
                                       ImportResult result) {
        try {
            byte[] rawBytes = readCapped(in, MAX_ENTRY_BYTES);
            if (rawBytes == null || rawBytes.length == 0) {
                result.failed++;
                if (displayName != null) result.failedNames.add(displayName);
                return null;
            }
            String rawText = new String(rawBytes, StandardCharsets.UTF_8);

            ConfigParser cp = new ConfigParser();
            cp.parseConfig(new StringReader(rawText));
            VpnProfile profile = cp.convertProfile();
            if (profile == null) {
                result.failed++;
                if (displayName != null) result.failedNames.add(displayName);
                return null;
            }

            // Preserve sanitized original .ovpn text so sharing exports clean configs
            profile.mOriginalConfig = VpnProfile.sanitizeOriginalConfig(rawText);

            // Preferred candidate name before deduplication/uniquification
            String baseName = baseName(displayName);
            String desiredName = (baseName != null && !baseName.isEmpty())
                    ? baseName
                    : (!TextUtils.isEmpty(profile.mName) ? profile.mName : "Imported profile");
            profile.mName = desiredName;

            // Check duplicate BEFORE renaming to "name (2)"!
            VpnProfile existing = findDuplicate(context, profile);
            if (existing != null) {
                if (options != null && !TextUtils.isEmpty(options.category)) {
                    existing.addToGroup(options.category);
                    ProfileManager.saveProfile(context, existing);
                }
                result.skippedDuplicates++;
                return null;
            }

            profile.mName = uniqueName(context, desiredName);

            applyOptions(context, profile, options);
            ProfileManager pm = ProfileManager.getInstance(context);
            pm.addProfile(profile);
            profile.addChangeLogEntry("Profile imported via Minab bulk import");
            ProfileManager.saveProfile(context, profile);
            if (options == null || !options.deferSaveList) {
                pm.saveProfileList(context);
            }
            result.imported++;
            result.importedProfiles.add(profile);
            return profile;
        } catch (Exception e) {
            result.failed++;
            if (displayName != null) result.failedNames.add(displayName);
            return null;
        }
    }

    /** Import every .ovpn/.conf entry of a zip stream. */
    public static ImportResult importZip(Context context, InputStream in, ImportOptions options) {
        return importZip(context, in, options, null);
    }

    /** Import every .ovpn/.conf entry of a zip stream, reporting live progress. */
    public static ImportResult importZip(Context context, InputStream in, ImportOptions options,
                                         ProgressListener listener) {
        ImportResult result = new ImportResult();
        boolean prevDefer = options != null && options.deferSaveList;
        ImportOptions batchOpts = options != null ? options : new ImportOptions();
        batchOpts.deferSaveList = true;

        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            int seen = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (!isOvpnName(name)) continue;
                if (++seen > MAX_FILES_PER_IMPORT) {
                    result.failed++;
                    result.failedNames.add(name + " (limit of " + MAX_FILES_PER_IMPORT + " files)");
                    if (listener != null) listener.onProgress(seen);
                    continue;
                }
                byte[] bytes = readCapped(zip, MAX_ENTRY_BYTES);
                if (bytes == null) {
                    result.failed++;
                    result.failedNames.add(name + " (too large)");
                    if (listener != null) listener.onProgress(seen);
                    continue;
                }
                importOne(context, new ByteArrayInputStream(bytes), fileNameOf(name), batchOpts, result);
                if (listener != null) listener.onProgress(seen);
            }
            if (!prevDefer && result.imported > 0) {
                ProfileManager.getInstance(context).saveProfileList(context);
            }
            if (seen == 0) {
                result.failed++;
                result.failedNames.add("zip contained no .ovpn/.conf files");
            }
        } catch (Exception e) {
            result.failed++;
            result.failedNames.add("zip: " + e.getMessage());
        } finally {
            batchOpts.deferSaveList = prevDefer;
        }
        return result;
    }

    /** True when an identical endpoint (host + port + protocol) already exists. */
    public static boolean isDuplicate(Context context, VpnProfile candidate) {
        return findDuplicate(context, candidate) != null;
    }

    public static VpnProfile findDuplicate(Context context, VpnProfile candidate) {
        if (candidate == null) return null;
        ProfileManager pm = ProfileManager.getInstance(context);
        String host = candidate.getPrimaryRemoteHost();
        String normHost = host == null ? "" : host.trim().toLowerCase(Locale.US);
        int port = candidate.getPrimaryRemotePort();
        boolean udp = candidate.mUseUdp;
        for (VpnProfile existing : pm.getProfiles()) {
            if (existing == candidate) continue;
            String exHost = existing.getPrimaryRemoteHost();
            String normExHost = exHost == null ? "" : exHost.trim().toLowerCase(Locale.US);
            if (!normHost.isEmpty()) {
                if (normHost.equals(normExHost)
                        && existing.getPrimaryRemotePort() == port
                        && existing.mUseUdp == udp) {
                    return existing;
                }
            } else if (TextUtils.equals(existing.mName, candidate.mName)
                    && existing.getPrimaryRemotePort() == port
                    && existing.mUseUdp == udp) {
                return existing;
            }
        }
        return null;
    }

    /** Unique profile name: "name", "name (2)", "name (3)", ... */
    public static String uniqueName(Context context, String wanted) {
        ProfileManager pm = ProfileManager.getInstance(context);
        if (pm.getProfileByName(wanted) == null) return wanted;
        int i = 2;
        while (pm.getProfileByName(wanted + " (" + i + ")") != null) i++;
        return wanted + " (" + i + ")";
    }

    private static void applyOptions(Context context, VpnProfile profile, ImportOptions options) {
        if (options == null) return;
        if (options.category != null && !options.category.isEmpty()) {
            profile.setCategory(options.category);
        }
        if (options.applyCredentialsToAll && profile.isUserPWAuth()) {
            if (options.username != null) profile.mUsername = options.username;
            if (options.password != null) profile.mPassword = options.password;
        }
        MinabAllowedApps.applyStoredToProfile(context, profile);
    }

    private static byte[] readCapped(InputStream in, int maxBytes) throws IOException {
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

    private static String baseName(String displayName) {
        if (displayName == null) return null;
        String name = fileNameOf(displayName);
        if (name.toLowerCase(Locale.US).endsWith(".ovpn")) {
            name = name.substring(0, name.length() - 5);
        } else if (name.toLowerCase(Locale.US).endsWith(".conf")) {
            name = name.substring(0, name.length() - 5);
        }
        return name;
    }
}
