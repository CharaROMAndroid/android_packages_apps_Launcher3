package com.android.launcher3.customization;

import android.content.ContextWrapper;
import android.content.SharedPreferences;
import com.android.launcher3.LauncherSettings;
import com.android.launcher3.util.ComponentKey;
import java.util.*;

/**
 * Behavioural tests for the real InfoBottomSheet/IconDatabase logic. Android framework and launcher
 * dependencies are STUBS (see run.sh); this is not an on-device or full platform test.
 */
public class InfoSheetLogicTest {
    static int failures = 0;
    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }

    static class FakePrefs implements SharedPreferences {
        final Map<String, String> m = new HashMap<>();
        public Map<String, ?> getAll() { return m; }
        public String getString(String k, String d) { return m.containsKey(k) ? m.get(k) : d; }
        public Set<String> getStringSet(String k, Set<String> d) { return d; }
        public int getInt(String k, int d) { return d; }
        public long getLong(String k, long d) { return d; }
        public float getFloat(String k, float d) { return d; }
        public boolean getBoolean(String k, boolean d) { return d; }
        public boolean contains(String k) { return m.containsKey(k); }
        public Editor edit() {
            return new Editor() {
                public Editor putString(String k, String v) { m.put(k, v); return this; }
                public Editor remove(String k) { m.remove(k); return this; }
                public Editor putStringSet(String k, Set<String> v) { return this; }
                public Editor putInt(String k, int v) { return this; }
                public Editor putLong(String k, long v) { return this; }
                public Editor putFloat(String k, float v) { return this; }
                public Editor putBoolean(String k, boolean v) { return this; }
                public Editor clear() { m.clear(); return this; }
                public boolean commit() { return true; }
                public void apply() {}
            };
        }
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
    }

    static class FakeContext extends ContextWrapper {
        final FakePrefs global = new FakePrefs();
        final FakePrefs perApp = new FakePrefs();
        FakeContext() { super(null); }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return name.equals("p") ? global : perApp;
        }
    }

    public static void main(String[] args) {
        final int APP = LauncherSettings.Favorites.ITEM_TYPE_APPLICATION;

        check("launcher+current user+app, not recents -> sheet",
                InfoBottomSheet.shouldRouteToSheet(true, true, APP, false));
        check("recents visible -> system info",
                !InfoBottomSheet.shouldRouteToSheet(true, true, APP, true));
        check("not Launcher -> system info",
                !InfoBottomSheet.shouldRouteToSheet(false, true, APP, false));
        check("canShow false -> system info",
                !InfoBottomSheet.shouldRouteToSheet(true, false, APP, false));
        check("non-application item type -> system info",
                !InfoBottomSheet.shouldRouteToSheet(true, true, APP + 1, false));
        check("current user matches", InfoBottomSheet.isCurrentUser("u0", "u0"));
        check("other profile rejected", !InfoBottomSheet.isCurrentUser("u10", "u0"));
        check("null user rejected", !InfoBottomSheet.isCurrentUser(null, "u0"));

        FakeContext c = new FakeContext();
        ComponentKey key = new ComponentKey("com.a/.A#user");
        IconDatabase.setGlobal(c, "pack.global");
        check("effective pack follows global without override",
                IconDatabase.getByComponent(c, key).equals("pack.global"));
        check("other pack sets override",
                !InfoBottomSheet.applyPackChoice(c, key, "pack.other")
                        && IconDatabase.getByComponent(c, key).equals("pack.other"));
        check("global pack resets override",
                InfoBottomSheet.applyPackChoice(c, key, "pack.global")
                        && !c.perApp.contains(key.toString())
                        && IconDatabase.getByComponent(c, key).equals("pack.global"));
        check("Default (empty) differs from global -> explicit override",
                !InfoBottomSheet.applyPackChoice(c, key, IconDatabase.VALUE_DEFAULT)
                        && IconDatabase.getByComponent(c, key).equals(""));
        IconDatabase.setExplicitIconForComponent(c, key, "pack.x", "drawable_1");
        check("explicit icon override readable",
                IconDatabase.getByComponent(c, key).equals("pack.x")
                        && "drawable_1".equals(IconDatabase.getExplicitDrawableForComponent(c, key)));
        InfoBottomSheet.applyPackChoice(c, key, "pack.other");
        check("choosing pack replaces explicit drawable",
                IconDatabase.getExplicitDrawableForComponent(c, key) == null);
        IconDatabase.setExplicitIconForComponent(c, key, "pack.x", "drawable_1");
        InfoBottomSheet.applyPackChoice(c, key, "pack.global");
        check("choosing global clears explicit drawable",
                IconDatabase.getExplicitDrawableForComponent(c, key) == null);
        IconDatabase.setGlobal(c, "");
        check("global empty: Default resets override",
                InfoBottomSheet.applyPackChoice(c, key, "") && !c.perApp.contains(key.toString()));

        InfoBottomSheet.ChooserToken t = new InfoBottomSheet.ChooserToken();
        int first = t.next();
        check("fresh load is current", t.isCurrent(first));
        t.next();
        int second = t.next();
        check("old list after back+reopen is stale", !t.isCurrent(first));
        check("new list is current", t.isCurrent(second));
        t.next();
        check("load after close is stale", !t.isCurrent(second));

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
