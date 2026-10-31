package ru.geministation;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only enabled, exported Android/TV launcher activities; never enumerate services or hidden components. */
final class VisibleApps {
    private static final int MAX_APPS = 200;
    static final class App {
        final String label, category;
        final ComponentName component;
        List<String> links = java.util.Collections.emptyList();
        App(String label, String category, ComponentName component) {
            this.label = label; this.category = category; this.component = component;
        }
    }
    private final List<App> apps;
    private VisibleApps(List<App> apps) { this.apps = apps; }
    static VisibleApps empty() { return new VisibleApps(new ArrayList<>()); }
    int size() { return apps.size(); }
    App get(int index) { return apps.get(index); }

    static boolean accessible(Context context, ActivityInfo activity) {
        if (activity == null || activity.applicationInfo == null) return false;
        boolean permission = activity.permission == null || activity.permission.isEmpty()
                || context.checkSelfPermission(activity.permission) == PackageManager.PERMISSION_GRANTED;
        return DeviceToolSpec.eligible(true, activity.applicationInfo.enabled, activity.enabled, activity.exported,
                permission, context.getPackageName().equals(activity.packageName));
    }
    static VisibleApps load(Context context) {
        PackageManager pm = context.getPackageManager();
        Map<String, App> byPackage = new HashMap<>();
        // TV launcher entries take precedence when a package exposes both forms.
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
                ActivityInfo activity = info.activityInfo;
                if (!accessible(context, activity) || byPackage.containsKey(activity.packageName)) continue;
                CharSequence name = info.loadLabel(pm);
                String label = name == null ? "" : name.toString().replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").trim();
                if (label.isEmpty()) continue;
                if (label.length() > 80) label = label.substring(0, 80);
                byPackage.put(activity.packageName, new App(label, category, new ComponentName(activity.packageName, activity.name)));
            }
        }
        List<App> apps = new ArrayList<>(byPackage.values());
        apps.sort(Comparator.comparing((App a) -> a.label.toLowerCase(Locale.ROOT))
                .thenComparing(a -> a.component.flattenToString()));
        if (apps.size() > MAX_APPS) apps = new ArrayList<>(apps.subList(0, MAX_APPS));
        for (App app : apps) {
            app.links = new ArrayList<>(ManifestLinkHints.load(context, app.component.getPackageName()));
            // Some APK manifests are unavailable or split; probe common YouTube links with Android's resolver.
            for (String base : new String[]{"https://www.youtube.com", "https://youtu.be"}) {
                String probe = base.contains("youtu.be") ? base + "/aaaaaaaaaaa" : base + "/watch?v=aaaaaaaaaaa";
                if (!app.links.contains(base) && linkIntent(context, app, probe) != null) app.links.add(base);
            }
        }
        return new VisibleApps(apps);
    }
    JSONArray promptList() throws Exception {
        JSONArray result = new JSONArray();
        for (int i = 0; i < apps.size(); i++) result.put(new JSONObject().put("id", i).put("name", apps.get(i).label)
                .put("links", new JSONArray(apps.get(i).links)));
        return result;
    }
    /** Re-check that the component is still an enabled and exported launcher activity. */
    static boolean stillVisible(Context context, App chosen) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(chosen.category).setPackage(chosen.component.getPackageName());
        for (ResolveInfo result : context.getPackageManager().queryIntentActivities(query, 0)) {
            ActivityInfo info = result.activityInfo;
            if (accessible(context, info) && chosen.component.equals(new ComponentName(info.packageName, info.name))) return true;
        }
        return false;
    }
    /** Resolve a concrete URL to an exported handler in the same visible package. */
    static Intent linkIntent(Context context, App chosen, String url) {
        if (!LinkPolicy.allowed(url) || !stillVisible(context, chosen)) return null;
        Intent query = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
                .setPackage(chosen.component.getPackageName());
        for (ResolveInfo result : context.getPackageManager().queryIntentActivities(query, PackageManager.MATCH_DEFAULT_ONLY)) {
            ActivityInfo info = result.activityInfo;
            if (accessible(context, info) && chosen.component.getPackageName().equals(info.packageName))
                return new Intent(query).setComponent(new ComponentName(info.packageName, info.name));
        }
        return null;
    }
}
