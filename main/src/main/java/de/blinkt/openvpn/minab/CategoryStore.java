/*
 * Minab Children — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab Children contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * CategoryStore: manages dynamic IP-based country groups and user-created
 * groups. Never pre-seeds default country groups — country groups only appear
 * when imported configs belong to them (or when the user manually creates a
 * group). Also defines sentinels for Favorites, Active, and Inactive tabs.
 */

package de.blinkt.openvpn.minab;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.ProfileManager;

public final class CategoryStore {
    public static final String DEFAULT_CATEGORY = "Uncategorized";

    /** Pseudo-tab sentinels (control-char prefixed so they never collide with group names). */
    public static final String FAVORITES_TAB = "\u0001favorites";
    public static final String ACTIVE_TAB = "\u0001active";
    public static final String INACTIVE_TAB = "\u0001inactive";

    private static final String PREFS_NAME = "MinabCategories";
    private static final String KEY_CATEGORIES = "categories";
    /** Groups explicitly created by the user via "+ New group". */
    private static final String KEY_MANUAL_CATEGORIES = "manual_categories";
    private static final String KEY_ORDER = "order";

    private CategoryStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Normalise a user-supplied category name; null/blank -> default. */
    public static String normalise(String category) {
        if (category == null) return DEFAULT_CATEGORY;
        String trimmed = category.trim();
        if (trimmed.isEmpty()) return DEFAULT_CATEGORY;
        if (trimmed.length() > 64) trimmed = trimmed.substring(0, 64).trim();
        return trimmed;
    }

    /** True if the value is a built-in pseudo-tab (favorites / active / inactive). */
    public static boolean isPseudoTab(String category) {
        return FAVORITES_TAB.equals(category)
                || ACTIVE_TAB.equals(category)
                || INACTIVE_TAB.equals(category);
    }

    /**
     * All currently active categories: groups actually used by existing
     * profiles plus any group explicitly created by the user, with
     * DEFAULT_CATEGORY first (used by Assign / Copy / Move / Backup dialogs).
     */
    public static List<String> getCategories(Context context) {
        TreeSet<String> all = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> manual = prefs(context).getStringSet(KEY_MANUAL_CATEGORIES, null);
        if (manual != null) all.addAll(manual);
        all.add(DEFAULT_CATEGORY);
        try {
            for (VpnProfile p : ProfileManager.getInstance(context).getProfiles()) {
                all.add(normalise(p.getCategory()));
            }
        } catch (Exception ignored) {
        }
        List<String> out = new ArrayList<>(all);
        out.remove(DEFAULT_CATEGORY);
        out.add(0, DEFAULT_CATEGORY);
        return Collections.unmodifiableList(out);
    }

    /**
     * Categories to show as filter chips on Home & Configs: ONLY country/group
     * tabs that currently have at least one profile (plus user-created groups).
     * DEFAULT_CATEGORY ("Uncategorized") is included ONLY when at least one
     * profile is actually uncategorized, so a fresh/empty install never shows
     * phantom default groups.
     */
    public static List<String> getDisplayCategories(Context context, Collection<VpnProfile> profiles) {
        TreeSet<String> active = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> manual = prefs(context).getStringSet(KEY_MANUAL_CATEGORIES, null);
        if (manual != null) {
            for (String m : manual) {
                if (!DEFAULT_CATEGORY.equals(m)) active.add(normalise(m));
            }
        }
        boolean hasUncategorized = false;
        if (profiles != null) {
            for (VpnProfile p : profiles) {
                String cat = normalise(p.getCategory());
                if (DEFAULT_CATEGORY.equals(cat)) {
                    hasUncategorized = true;
                } else {
                    active.add(cat);
                }
            }
        }
        if (hasUncategorized) {
            active.add(DEFAULT_CATEGORY);
        }
        return applySavedOrder(context, new ArrayList<>(active));
    }

    /** Record an auto-detected or assigned category. */
    public static void addCategory(Context context, String category) {
        String name = normalise(category);
        if (DEFAULT_CATEGORY.equals(name)) return;
        Set<String> stored = new HashSet<>();
        Set<String> old = prefs(context).getStringSet(KEY_CATEGORIES, null);
        if (old != null) stored.addAll(old);
        if (stored.add(name)) {
            prefs(context).edit().putStringSet(KEY_CATEGORIES, stored).apply();
        }
    }

    /** Record a group explicitly created by the user so it stays visible even while empty. */
    public static void addManualCategory(Context context, String category) {
        String name = normalise(category);
        if (DEFAULT_CATEGORY.equals(name)) return;
        addCategory(context, name);
        Set<String> manual = new HashSet<>();
        Set<String> old = prefs(context).getStringSet(KEY_MANUAL_CATEGORIES, null);
        if (old != null) manual.addAll(old);
        if (manual.add(name)) {
            prefs(context).edit().putStringSet(KEY_MANUAL_CATEGORIES, manual).apply();
        }
    }

    /** Rename a category everywhere: store + every profile using it. */
    public static void renameCategory(Context context, String oldName, String newName) {
        String from = normalise(oldName);
        String to = normalise(newName);
        if (from.equals(to)) return;
        addManualCategory(context, to);
        try {
            ProfileManager pm = ProfileManager.getInstance(context);
            for (VpnProfile p : pm.getProfiles()) {
                if (from.equals(normalise(p.getCategory()))) {
                    p.setCategory(to);
                    ProfileManager.saveProfile(context, p);
                }
            }
        } catch (Exception ignored) {
        }
        removeCategoryNameOnly(context, from);
    }

    /**
     * Delete a category name from the store. Profiles that used it fall
     * back to "Uncategorized" (their mCategory is rewritten and saved).
     * The default category itself can never be deleted.
     */
    public static void deleteCategory(Context context, String category) {
        String name = normalise(category);
        if (DEFAULT_CATEGORY.equals(name)) return;
        try {
            ProfileManager pm = ProfileManager.getInstance(context);
            for (VpnProfile p : pm.getProfiles()) {
                if (name.equals(normalise(p.getCategory()))) {
                    p.setCategory(DEFAULT_CATEGORY);
                    ProfileManager.saveProfile(context, p);
                }
            }
        } catch (Exception ignored) {
        }
        removeCategoryNameOnly(context, name);
    }

    private static void removeCategoryNameOnly(Context context, String name) {
        SharedPreferences p = prefs(context);
        SharedPreferences.Editor editor = p.edit();
        boolean changed = false;
        Set<String> old = p.getStringSet(KEY_CATEGORIES, null);
        if (old != null && old.contains(name)) {
            Set<String> copy = new HashSet<>(old);
            copy.remove(name);
            editor.putStringSet(KEY_CATEGORIES, copy);
            changed = true;
        }
        Set<String> oldManual = p.getStringSet(KEY_MANUAL_CATEGORIES, null);
        if (oldManual != null && oldManual.contains(name)) {
            Set<String> copy = new HashSet<>(oldManual);
            copy.remove(name);
            editor.putStringSet(KEY_MANUAL_CATEGORIES, copy);
            changed = true;
        }
        if (changed) editor.apply();
    }

    /** Display order of all known categories (used by Reorder & Manage dialogs). */
    public static List<String> getOrderedCategories(Context context) {
        return applySavedOrder(context, getCategories(context));
    }

    private static List<String> applySavedOrder(Context context, List<String> known) {
        List<String> saved = readOrder(context);
        List<String> out = new ArrayList<>();
        for (String name : saved) {
            if (known.contains(name) && !out.contains(name)) out.add(name);
        }
        List<String> missing = new ArrayList<>();
        for (String name : known) {
            if (!out.contains(name)) missing.add(name);
        }
        // Keep DEFAULT_CATEGORY at the end when inserting newly discovered groups.
        boolean hadDefaultInMissing = missing.remove(DEFAULT_CATEGORY);
        int uncIdx = out.indexOf(DEFAULT_CATEGORY);
        if (uncIdx < 0) {
            out.addAll(missing);
            if (hadDefaultInMissing) out.add(DEFAULT_CATEGORY);
        } else {
            out.addAll(uncIdx, missing);
        }
        return out;
    }

    /** Save the group order set by the reorder dialog. */
    public static void saveOrder(Context context, List<String> order) {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (String name : order) arr.put(name);
        prefs(context).edit().putString(KEY_ORDER, arr.toString()).apply();
    }

    private static List<String> readOrder(Context context) {
        List<String> out = new ArrayList<>();
        String raw = prefs(context).getString(KEY_ORDER, null);
        if (raw == null || raw.isEmpty()) return out;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String name = arr.optString(i, null);
                if (name != null && !name.isEmpty()) out.add(normalise(name));
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
