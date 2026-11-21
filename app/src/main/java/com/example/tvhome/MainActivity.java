package com.example.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal Apple-TV-style home screen. No libraries, no layouts XML, ~one file. */
public class MainActivity extends Activity {

    private static final int CARD_W = 232, CARD_H = 130, CARD_R = 22; // dp
    private static final float FOCUS_SCALE = 1.16f;

    private static class App {
        String label, pkg;
        Intent intent;
        Drawable banner, icon;
        int count, color, ambient;
    }

    private SharedPreferences prefs;
    private float d;
    private GradientDrawable bg;
    private int bgColor = 0xFF1B2233;
    private ValueAnimator bgAnim;
    private HorizontalScrollView scroll;
    private LinearLayout row;
    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reload(); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("usage", MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{bgColor, 0xFF05060A});
        root.setBackground(bg);

        // Clock, top right
        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        clock.setGravity(Gravity.END);
        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm a");
        time.setFormat24Hour("HH:mm");
        time.setTextSize(44);
        time.setTextColor(Color.WHITE);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, MMM d");
        date.setFormat24Hour("EEEE, MMM d");
        date.setTextSize(16);
        date.setTextColor(0xB3FFFFFF);
        date.setGravity(Gravity.END);
        clock.addView(time);
        clock.addView(date);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        cp.topMargin = dp(36);
        cp.rightMargin = dp(56);
        root.addView(clock, cp);

        // App row
        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(dp(60), 0, dp(60), 0);
        scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(false);
        scroll.addView(row, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL);
        sp.topMargin = dp(70);
        root.addView(scroll, sp);

        setContentView(root);
        reload();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
    }

    @Override protected void onStop() {
        super.onStop();
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); // Home pressed: jump back to first tile
        if (row.getChildCount() > 0) row.getChildAt(0).requestFocus();
    }

    @Override public void onBackPressed() { /* we are home */ }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // MENU key on the remote = app info (uninstall / force stop)
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) {
            View v = getCurrentFocus();
            if (v != null && v.getTag() instanceof App) { appInfo((App) v.getTag()); return true; }
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------------------------------------------------------------- data

    private void reload() {
        String keep = null;
        View cur = getCurrentFocus();
        if (cur != null && cur.getTag() instanceof App) keep = ((App) cur.getTag()).label;

        PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> found = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo r : pm.queryIntentActivities(q, 0)) {
                String p = r.activityInfo.packageName;
                if (p.equals(getPackageName()) || found.containsKey(p)) continue;
                found.put(p, r);
            }
        }

        List<App> apps = new ArrayList<>();
        for (ResolveInfo r : found.values()) {
            App a = new App();
            a.pkg = r.activityInfo.packageName;
            a.label = String.valueOf(r.loadLabel(pm));
            a.banner = r.activityInfo.loadBanner(pm);
            a.icon = r.loadIcon(pm);
            a.count = prefs.getInt(a.pkg, 0);
            a.intent = new Intent(Intent.ACTION_MAIN)
                    .setClassName(a.pkg, r.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            apps.add(a);
        }
        Collections.sort(apps, (x, y) -> x.count != y.count
                ? Integer.compare(y.count, x.count)
                : x.label.compareToIgnoreCase(y.label));

        App settings = new App();
        settings.label = "Settings";
        settings.pkg = null;
        settings.icon = getResources().getDrawable(android.R.drawable.ic_menu_preferences);
        settings.intent = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        apps.add(settings);

        row.removeAllViews();
        View focusMe = null;
        for (App a : apps) {
            View t = makeTile(a);
            row.addView(t);
            if (focusMe == null || a.label.equals(keep)) { if (focusMe == null || keep != null) focusMe = t; }
        }
        if (focusMe != null) focusMe.requestFocus();
    }

    // ---------------------------------------------------------------- UI

    private View makeTile(final App a) {
        final int w = dp(CARD_W), h = dp(CARD_H);
        analyzeColors(a);

        final LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setPivotX(w / 2f);
        col.setPivotY(h / 2f);

        final FrameLayout card = new FrameLayout(this);
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{lighten(a.color), a.color});
        card.setBackground(fill);
        card.setElevation(dp(6));
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(CARD_R));
            }
        });
        card.setClipToOutline(true);

        ImageView img = new ImageView(this);
        if (a.banner != null) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageDrawable(a.banner);
            card.addView(img, new FrameLayout.LayoutParams(w, h));
        } else {
            img.setImageDrawable(a.icon);
            card.addView(img, new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER));
        }

        final View sheen = new View(this); // soft highlight that appears on focus
        sheen.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x33FFFFFF, 0x00FFFFFF}));
        sheen.setAlpha(0f);
        card.addView(sheen, new FrameLayout.LayoutParams(w, h));
        col.addView(card, new LinearLayout.LayoutParams(w, h));

        final TextView label = new TextView(this);
        label.setText(a.label);
        label.setTextColor(Color.WHITE);
        label.setTextSize(17);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setAlpha(0f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        col.addView(label, lp);

        FrameLayout cell = new FrameLayout(this);
        cell.setClipChildren(false);
        cell.setPadding(dp(16), dp(36), dp(16), dp(36));
        cell.addView(col);
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setTag(a);
        cell.setDefaultFocusHighlightEnabled(false);
        cell.setOnClickListener(v -> launch(a));
        cell.setOnLongClickListener(v -> { if (a.pkg != null) appInfo(a); return true; });
        cell.setOnFocusChangeListener((v, f) -> {
            long ms = 180;
            float s = f ? FOCUS_SCALE : 1f;
            col.animate().scaleX(s).scaleY(s).setDuration(ms).setInterpolator(new DecelerateInterpolator()).start();
            card.animate().translationZ(f ? dp(20) : 0).setDuration(ms).start();
            label.animate().alpha(f ? 1f : 0f).setDuration(ms).start();
            sheen.animate().alpha(f ? 1f : 0f).setDuration(ms).start();
            if (f) {
                int target = v.getLeft() + v.getWidth() / 2 - scroll.getWidth() / 2;
                scroll.smoothScrollTo(Math.max(0, target), 0);
                tintBackground(a.ambient);
            }
        });
        return cell;
    }

    private void tintBackground(int to) {
        if (bgAnim != null) bgAnim.cancel();
        bgAnim = ValueAnimator.ofObject(new ArgbEvaluator(), bgColor, to);
        bgAnim.setDuration(450);
        bgAnim.addUpdateListener(an -> {
            bgColor = (Integer) an.getAnimatedValue();
            bg.setColors(new int[]{bgColor, 0xFF05060A});
        });
        bgAnim.start();
    }

    private void launch(App a) {
        try {
            startActivity(a.intent);
            if (a.pkg != null) prefs.edit().putInt(a.pkg, a.count + 1).apply();
        } catch (Exception e) {
            Toast.makeText(this, "Can't open " + a.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void appInfo(App a) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + a.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- colours

    /** Average the icon/banner at 8x8 and derive a card colour + a dark ambient background tint. */
    private void analyzeColors(App a) {
        int avg = 0xFF3A4660;
        try {
            Drawable src = a.banner != null ? a.banner : a.icon;
            Drawable.ConstantState cs = src.getConstantState();
            Drawable dr = cs != null ? cs.newDrawable().mutate() : src;
            Bitmap bm = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            dr.setBounds(0, 0, 8, 8);
            dr.draw(new Canvas(bm));
            long r = 0, g = 0, b = 0, n = 0;
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++;
            }
            bm.recycle();
            if (n > 0) avg = Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
        } catch (Exception ignored) { }
        float[] hsv = new float[3];
        Color.colorToHSV(avg, hsv);
        if (hsv[1] < 0.12f) { hsv[0] = 218f; hsv[1] = 0.28f; } // greys/whites -> cool slate
        float[] c = {hsv[0], Math.max(0.35f, hsv[1]), 0.50f};
        float[] am = {hsv[0], Math.max(0.40f, hsv[1]), 0.30f};
        a.color = Color.HSVToColor(c);
        a.ambient = Color.HSVToColor(am);
    }

    private static int lighten(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[2] = Math.min(1f, hsv[2] + 0.18f);
        return Color.HSVToColor(hsv);
    }

    private int dp(float v) { return Math.round(v * d); }
}
