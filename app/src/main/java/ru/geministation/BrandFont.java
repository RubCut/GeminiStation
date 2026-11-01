package ru.geministation;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Use the bundled open Google Sans locally; no font download is needed. */
final class BrandFont {
    private static volatile Typeface font;
    static Typeface get(Context context) {
        Typeface result = font;
        if (result == null) try { font = result = Typeface.createFromAsset(context.getAssets(), "fonts/google_sans.ttf"); }
        catch (RuntimeException e) { return Typeface.DEFAULT; }
        return result;
    }
    static void apply(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView)view;
            int style = text.getTypeface() == null ? Typeface.NORMAL : text.getTypeface().getStyle();
            text.setTypeface(get(view.getContext()), style);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) apply(group.getChildAt(i));
        }
    }
    private BrandFont() { }
}
