package ru.geministation;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.XmlResourceParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.xmlpull.v1.XmlPullParser;

/** Best-effort web intent hints from the public APK manifest of a visible app. */
final class ManifestLinkHints {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final int MAX_HINTS = 30;
    static List<String> load(Context context, String packageName) {
        Set<String> hints = new LinkedHashSet<>();
        XmlResourceParser xml = null;
        try {
            xml = context.createPackageContext(packageName, 0).getAssets().openXmlResourceParser("AndroidManifest.xml");
            boolean usableActivity = false;
            int activityDepth = -1, filterDepth = -1;
            LinkHintFilter filter = null;
            for (int event = xml.next(); event != XmlPullParser.END_DOCUMENT; event = xml.next()) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = xml.getName();
                    if (("activity".equals(tag) || "activity-alias".equals(tag)) && activityDepth < 0) {
                        activityDepth = xml.getDepth();
                        String name = xml.getAttributeValue(ANDROID, "name");
                        usableActivity = accessible(context, packageName, name);
                    } else if (usableActivity && "intent-filter".equals(tag) && filter == null) {
                        filterDepth = xml.getDepth(); filter = new LinkHintFilter();
                    } else if (filter != null && "action".equals(tag)) {
                        if (Intent.ACTION_VIEW.equals(xml.getAttributeValue(ANDROID, "name"))) filter.view = true;
                    } else if (filter != null && "category".equals(tag)) {
                        String name = xml.getAttributeValue(ANDROID, "name");
                        if (Intent.CATEGORY_BROWSABLE.equals(name)) filter.browsable = true;
                        if (Intent.CATEGORY_DEFAULT.equals(name)) filter.defaultCategory = true;
                    } else if (filter != null && "data".equals(tag)) {
                        filter.data(xml.getAttributeValue(ANDROID, "scheme"), xml.getAttributeValue(ANDROID, "host"));
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    if (filter != null && xml.getDepth() == filterDepth && "intent-filter".equals(xml.getName())) {
                        hints.addAll(filter.hints()); filter = null; filterDepth = -1;
                    }
                    if (activityDepth == xml.getDepth() && ("activity".equals(xml.getName()) || "activity-alias".equals(xml.getName()))) {
                        activityDepth = -1; usableActivity = false;
                    }
                }
                if (hints.size() >= MAX_HINTS) break;
            }
        } catch (Exception | LinkageError ignored) {
            // Some APKs/firmware do not expose their manifest; exact URL resolution still works.
        } finally { if (xml != null) xml.close(); }
        List<String> result = new ArrayList<>(hints);
        Collections.sort(result);
        return result.size() > MAX_HINTS ? new ArrayList<>(result.subList(0, MAX_HINTS)) : result;
    }
    private static boolean accessible(Context context, String packageName, String name) {
        if (name == null || name.isEmpty()) return false;
        if (name.charAt(0) == '.') name = packageName + name;
        else if (!name.contains(".")) name = packageName + "." + name;
        try {
            ActivityInfo info = context.getPackageManager().getActivityInfo(new ComponentName(packageName, name), 0);
            return VisibleApps.accessible(context, info);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) { return false; }
    }
    private ManifestLinkHints() { }
}
