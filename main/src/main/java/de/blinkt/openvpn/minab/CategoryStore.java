/*
 * Minab Children — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab Children contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * CategoryStore: manages dynamic IP-based country groups and user-created
 * groups. Supports multi-group membership per profile, strict auto vs.
 * manual group tracking, and empty-group cleanup.
 */

package de.blinkt.openvpn.minab;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
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

    public static synchronized boolean isManualCategory(Context context, String category) {
        String name = normalise(category);
        if (DEFAULT_CATEGORY.equalsIgnoreCase(name)) return false;
        Set<String> manual = prefs(context).getStringSet(KEY_MANUAL_CATEGORIES, null);
        if (manual == null) return false;
        for (String m : manual) {
            if (name.equalsIgnoreCase(normalise(m))) return true;
        }
        return false;
    }

    public static synchronized Set<String> getManualCategories(Context context) {
        Set<String> manual = prefs(context).getStringSet(KEY_MANUAL_CATEGORIES, null);
        Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (manual != null) {
            for (String m : manual) {
                String n = normalise(m);
                if (!DEFAULT_CATEGORY.equalsIgnoreCase(n)) out.add(n);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /**
     * All currently active categories: groups actually used by existing
     * profiles (including multi-group memberships) plus any manual group,
     * with DEFAULT_CATEGORY first (used by Assign / Copy / Move / Backup dialogs).
     */
    public static List<String> getCategories(Context context) {
        TreeSet<String> all = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> manual = prefs(context).getStringSet(KEY_MANUAL_CATEGORIES, null);
        if (manual != null) {
            for (String m : manual) {
                all.add(normalise(m));
            }
        }
        all.add(DEFAULT_CATEGORY);
        try {
            for (VpnProfile p : ProfileManager.getInstance(context).getProfiles()) {
                for (String g : p.getAllGroups()) {
                    all.add(normalise(g));
                }
            }
        } catch (Exception ignored) {
        }
        List<String> out = new ArrayList<>(all);
        out.remove(DEFAULT_CATEGORY);
        out.add(0, DEFAULT_CATEGORY);
        return Collections.unmodifiableList(out);
    }

    /**
     * Categories to show as filter chips on Home & Configs: country/group
     * tabs that currently have at least one profile plus user-created manual groups.
     */
    public static List<String> getDisplayCategories(Context context, Collection<VpnProfile> profiles) {
        TreeSet<String> active = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> manual = prefs(context).getStringSet(KEY_MANUAL_CATEGORIES, null);
        if (manual != null) {
            for (String m : manual) {
                String n = normalise(m);
                if (!DEFAULT_CATEGORY.equals(n)) active.add(n);
            }
        }
        boolean hasUncategorized = false;
        if (profiles != null) {
            for (VpnProfile p : profiles) {
                for (String g : p.getAllGroups()) {
                    String cat = normalise(g);
                    if (DEFAULT_CATEGORY.equals(cat)) {
                        hasUncategorized = true;
                    } else {
                        active.add(cat);
                    }
                }
            }
        }
        if (hasUncategorized) {
            active.add(DEFAULT_CATEGORY);
        }
        return applySavedOrder(context, new ArrayList<>(active));
    }

    /** Auto-detected categories (in store or saved order) currently having 0 profiles. */
    public static synchronized List<String> getEmptyAutoCategories(Context context, Collection<VpnProfile> profiles) {
        Set<String> populated = populatedGroupsSet(profiles);
        Set<String> manual = getManualCategories(context);
        TreeSet<String> emptyAuto = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        Set<String> stored = prefs(context).getStringSet(KEY_CATEGORIES, null);
        if (stored != null) {
            for (String c : stored) {
                String n = normalise(c);
                if (!DEFAULT_CATEGORY.equalsIgnoreCase(n)
                        && !containsIgnoreCase(manual, n)
                        && !containsIgnoreCase(populated, n)) {
                    emptyAuto.add(n);
                }
            }
        }
        for (String c : readOrder(context)) {
            String n = normalise(c);
            if (!DEFAULT_CATEGORY.equalsIgnoreCase(n)
                    && !containsIgnoreCase(manual, n)
                    && !containsIgnoreCase(populated, n)) {
                emptyAuto.add(n);
            }
        }
        return new ArrayList<>(emptyAuto);
    }

    /** User-created manual groups currently having 0 profiles. */
    public static synchronized List<String> getEmptyManualCategories(Context context, Collection<VpnProfile> profiles) {
        Set<String> populated = populatedGroupsSet(profiles);
        TreeSet<String> emptyManual = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String m : getManualCategories(context)) {
            if (!containsIgnoreCase(populated, m)) {
                emptyManual.add(m);
            }
        }
        return new ArrayList<>(emptyManual);
    }

    /** Clean empty auto groups (and optionally empty manual groups). Returns number removed. */
    public static synchronized int cleanEmptyCategories(
            Context context, Collection<VpnProfile> profiles, boolean includeManual) {
        List<String> autoEmpty = getEmptyAutoCategories(context, profiles);
        List<String> manualEmpty = includeManual
                ? getEmptyManualCategories(context, profiles)
                : Collections.emptyList();
        Set<String> toRemove = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        toRemove.addAll(autoEmpty);
        toRemove.addAll(manualEmpty);
        if (toRemove.isEmpty()) return 0;

        SharedPreferences p = prefs(context);
        SharedPreferences.Editor editor = p.edit();

        Set<String> old = p.getStringSet(KEY_CATEGORIES, null);
        if (old != null) {
            Set<String> copy = new HashSet<>();
            for (String item : old) {
                if (!containsIgnoreCase(toRemove, normalise(item))) copy.add(item);
            }
            editor.putStringSet(KEY_CATEGORIES, copy);
        }

        if (includeManual) {
            Set<String> oldManual = p.getStringSet(KEY_MANUAL_CATEGORIES, null);
            if (oldManual != null) {
                Set<String> copy = new HashSet<>();
                for (String item : oldManual) {
                    if (!containsIgnoreCase(toRemove, normalise(item))) copy.add(item);
                }
                editor.putStringSet(KEY_MANUAL_CATEGORIES, copy);
            }
        }

        List<String> order = readOrder(context);
        if (!order.isEmpty()) {
            List<String> newOrder = new ArrayList<>();
            for (String item : order) {
                if (!containsIgnoreCase(toRemove, item)) newOrder.add(item);
            }
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String item : newOrder) arr.put(item);
            editor.putString(KEY_ORDER, arr.toString());
        }
        editor.apply();
        return toRemove.size();
    }

    private static Set<String> populatedGroupsSet(Collection<VpnProfile> profiles) {
        Set<String> populated = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (profiles != null) {
            for (VpnProfile p : profiles) {
                for (String g : p.getAllGroups()) {
                    populated.add(normalise(g));
                }
            }
        }
        return populated;
    }

    private static boolean containsIgnoreCase(Collection<String> set, String target) {
        for (String item : set) {
            if (item != null && item.equalsIgnoreCase(target)) return true;
        }
        return false;
    }

    /** Record an auto-detected or assigned category (does NOT mark it manual). */
    public static synchronized void addCategory(Context context, String category) {
        String name = normalise(category);
        if (DEFAULT_CATEGORY.equals(name)) return;
        Set<String> stored = new HashSet<>();
        Set<String> old = prefs(context).getStringSet(KEY_CATEGORIES, null);
        if (old != null) stored.addAll(old);
        if (!containsIgnoreCase(stored, name) && stored.add(name)) {
            prefs(context).edit().putStringSet(KEY_CATEGORIES, stored).apply();
        }
    }

    /** Record a group explicitly created by the user. */
    public static synchronized void addManualCategory(Context context, String category) {
        String name = normalise(category);
        if (DEFAULT_CATEGORY.equals(name)) return;
        addCategory(context, name);
        Set<String> manual = new HashSet<>();
        Set<String> old = prefs(context).getStringSet(KEY_MANUAL_CATEGORIES, null);
        if (old != null) manual.addAll(old);
        if (!containsIgnoreCase(manual, name) && manual.add(name)) {
            prefs(context).edit().putStringSet(KEY_MANUAL_CATEGORIES, manual).apply();
        }
    }

    /** Rename a category everywhere: store + every profile using it (preserving manual vs auto). */
    public static synchronized void renameCategory(Context context, String oldName, String newName) {
        String from = normalise(oldName);
        String to = normalise(newName);
        if (from.equals(to) || DEFAULT_CATEGORY.equals(from)) return;
        boolean wasManual = isManualCategory(context, from);
        if (wasManual) {
            addManualCategory(context, to);
        } else {
            addCategory(context, to);
        }
        try {
            ProfileManager pm = ProfileManager.getInstance(context);
            for (VpnProfile p : pm.getProfiles()) {
                if (p.belongsToGroup(from)) {
                    LinkedHashSet<String> groups = p.getAllGroups();
                    LinkedHashSet<String> updated = new LinkedHashSet<>();
                    for (String g : groups) {
                        if (from.equalsIgnoreCase(g)) updated.add(to);
                        else updated.add(g);
                    }
                    p.setAllGroups(updated);
                    ProfileManager.saveProfile(context, p);
                }
            }
        } catch (Exception ignored) {
        }
        replaceInSavedOrder(context, from, to);
        removeCategoryNameOnly(context, from);
    }

    /**
     * Delete a category from the store and remove it from every profile
     * (profiles keep any other groups they belong to, or fall back to Uncategorized).
     */
    public static synchronized void deleteCategory(Context context, String category) {
        String name = normalise(category);
        if (DEFAULT_CATEGORY.equals(name)) return;
        try {
            ProfileManager pm = ProfileManager.getInstance(context);
            for (VpnProfile p : pm.getProfiles()) {
                if (p.belongsToGroup(name)) {
                    p.removeFromGroup(name);
                    ProfileManager.saveProfile(context, p);
                }
            }
        } catch (Exception ignored) {
        }
        removeFromSavedOrder(context, name);
        removeCategoryNameOnly(context, name);
    }

    private static void removeCategoryNameOnly(Context context, String name) {
        SharedPreferences p = prefs(context);
        SharedPreferences.Editor editor = p.edit();
        boolean changed = false;
        Set<String> old = p.getStringSet(KEY_CATEGORIES, null);
        if (old != null) {
            Set<String> copy = new HashSet<>();
            for (String item : old) {
                if (!name.equalsIgnoreCase(normalise(item))) copy.add(item);
                else changed = true;
            }
            if (changed) editor.putStringSet(KEY_CATEGORIES, copy);
        }
        Set<String> oldManual = p.getStringSet(KEY_MANUAL_CATEGORIES, null);
        if (oldManual != null) {
            boolean mChanged = false;
            Set<String> copy = new HashSet<>();
            for (String item : oldManual) {
                if (!name.equalsIgnoreCase(normalise(item))) copy.add(item);
                else mChanged = true;
            }
            if (mChanged) {
                editor.putStringSet(KEY_MANUAL_CATEGORIES, copy);
                changed = true;
            }
        }
        if (changed) editor.apply();
    }

    private static void replaceInSavedOrder(Context context, String from, String to) {
        List<String> order = readOrder(context);
        if (order.isEmpty()) return;
        List<String> updated = new ArrayList<>();
        for (String item : order) {
            String next = from.equalsIgnoreCase(item) ? to : item;
            if (!containsIgnoreCase(updated, next)) updated.add(next);
        }
        saveOrder(context, updated);
    }

    private static void removeFromSavedOrder(Context context, String name) {
        List<String> order = readOrder(context);
        if (order.isEmpty()) return;
        List<String> updated = new ArrayList<>();
        for (String item : order) {
            if (!name.equalsIgnoreCase(item)) updated.add(item);
        }
        saveOrder(context, updated);
    }

    /** Display order of all known categories (used by Reorder & Manage dialogs). */
    public static List<String> getOrderedCategories(Context context) {
        return applySavedOrder(context, getCategories(context));
    }

    private static List<String> applySavedOrder(Context context, List<String> known) {
        List<String> saved = readOrder(context);
        List<String> out = new ArrayList<>();
        for (String name : saved) {
            if (containsIgnoreCase(known, name) && !containsIgnoreCase(out, name)) {
                out.add(name);
            }
        }
        List<String> missing = new ArrayList<>();
        for (String name : known) {
            if (!containsIgnoreCase(out, name)) missing.add(name);
        }
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
    public static synchronized void saveOrder(Context context, List<String> order) {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (String name : order) arr.put(name);
        prefs(context).edit().putString(KEY_ORDER, arr.toString()).apply();
    }

    public static synchronized List<String> readOrder(Context context) {
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
