package cz.zlatylauncher;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Zlatý Launcher - lehký launcher bez jakýchkoli knihoven a XML layoutů.
 * Domovská obrazovka: hodiny, widgety, tlačítko čištění paměti, dock s 5 aplikacemi.
 * Tah prstem nahoru = menu aplikací. Dlouhé podržení = tapety, widgety, klávesnice.
 */
public class HomeActivity extends Activity {

    private static final int GOLD = 0xFFD4AF37;
    private static final int GOLD_LIGHT = 0xFFF3D98B;
    private static final int GOLD_DARK = 0xFF8A6D1D;
    private static final int DOCK_SIZE = 5;
    private static final int REQ_BIND = 11;
    private static final int REQ_CONFIGURE = 12;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static class AppEntry {
        String label;
        String pkg;
        ComponentName cn;
    }

    private static class Cell {
        ImageView icon;
        TextView label;
    }

    private SharedPreferences prefs;
    private PackageManager pm;
    private AppWidgetManager awm;
    private AppWidgetHost widgetHost;
    private float density;
    private int iconPx;

    private RootLayout root;
    private LinearLayout content;
    private LinearLayout dock;
    private LinearLayout widgetBox;
    private LinearLayout drawerBox;
    private GridView grid;
    private EditText search;
    private TextView cleanBtn;
    private AppAdapter adapter;

    private final List<AppEntry> allApps = new ArrayList<>();
    private final Map<String, AppEntry> appMap = new HashMap<>();
    private final String[] dockPkgs = new String[DOCK_SIZE];
    private final List<Integer> widgetIds = new ArrayList<>();
    private boolean drawerOpen;
    private int pendingWidgetId = -1;

    private LruCache<String, Bitmap> iconCache;
    private final ExecutorService iconExec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private GestureDetector gestures;

    // ------------------------------------------------------------------ životní cyklus

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pm = getPackageManager();
        prefs = getSharedPreferences("zlaty", MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        iconPx = dp(48);
        iconCache = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 16)) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
        awm = AppWidgetManager.getInstance(this);
        widgetHost = new AppWidgetHost(this, 1024);

        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
        }
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dy = e2.getY() - e1.getY();
                float dx = e2.getX() - e1.getX();
                if (Math.abs(dy) < Math.abs(dx) * 1.2f) return false;
                if (!drawerOpen && dy < -dp(60) && vy < -700) {
                    openDrawer();
                } else if (drawerOpen && dy > dp(100) && vy > 1200 && gridAtTop()) {
                    closeDrawer();
                }
                return false;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (drawerOpen || inView(dock, e) || inView(cleanBtn, e)) return;
                root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                showHomeMenu();
            }
        });

        buildUi();
        setContentView(root);

        restoreWidgets();
        loadApps();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
    }

    @Override
    protected void onStart() {
        super.onStart();
        try {
            widgetHost.startListening();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onStop() {
        try {
            widgetHost.stopListening();
        } catch (Exception ignored) {
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(pkgReceiver);
        } catch (Exception ignored) {
        }
        iconExec.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (drawerOpen) closeDrawer();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (drawerOpen) closeDrawer();
        // na domovské obrazovce zpět nedělá nic
    }

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            main.removeCallbacks(reloadApps);
            main.postDelayed(reloadApps, 600);
        }
    };

    private final Runnable reloadApps = new Runnable() {
        @Override
        public void run() {
            loadApps();
        }
    };

    // ------------------------------------------------------------------ UI

    private class RootLayout extends FrameLayout {
        RootLayout(Context c) {
            super(c);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            gestures.onTouchEvent(ev);
            return super.dispatchTouchEvent(ev);
        }
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    private GradientDrawable grad(GradientDrawable.Orientation o, int... colors) {
        return new GradientDrawable(o, colors);
    }

    private void buildUi() {
        root = new RootLayout(this);
        root.setBackground(grad(GradientDrawable.Orientation.TOP_BOTTOM, 0x77000000, 0x33000000, 0xAA000000));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(content, new FrameLayout.LayoutParams(MATCH, MATCH));

        // hodiny
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        time.setTextSize(64);
        time.setTextColor(GOLD_LIGHT);
        time.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        time.setShadowLayer(24, 0, 0, 0x99D4AF37);
        time.setIncludeFontPadding(false);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(WRAP, WRAP);
        tl.topMargin = dp(28);
        content.addView(time, tl);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE d. MMMM");
        date.setFormat12Hour("EEEE d. MMMM");
        date.setTextSize(16);
        date.setTextColor(GOLD);
        date.setLetterSpacing(0.1f);
        content.addView(date, new LinearLayout.LayoutParams(WRAP, WRAP));

        View line = new View(this);
        line.setBackground(grad(GradientDrawable.Orientation.LEFT_RIGHT, 0x00D4AF37, GOLD, 0x00D4AF37));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(dp(180), dp(1));
        ll.topMargin = dp(14);
        ll.bottomMargin = dp(10);
        content.addView(line, ll);

        // widgety
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        widgetBox = new LinearLayout(this);
        widgetBox.setOrientation(LinearLayout.VERTICAL);
        sv.addView(widgetBox, new ViewGroup.LayoutParams(MATCH, WRAP));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        sl.setMargins(dp(14), 0, dp(14), 0);
        content.addView(sv, sl);

        // tlačítko čištění paměti
        cleanBtn = new TextView(this);
        cleanBtn.setText("⚡  Vyčistit paměť");
        cleanBtn.setTextColor(0xFF0A0A0A);
        cleanBtn.setTextSize(14);
        cleanBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        cleanBtn.setGravity(Gravity.CENTER);
        cleanBtn.setPadding(dp(22), dp(10), dp(22), dp(10));
        GradientDrawable cb = grad(GradientDrawable.Orientation.LEFT_RIGHT, GOLD_LIGHT, GOLD, GOLD_DARK);
        cb.setCornerRadius(dp(22));
        cleanBtn.setBackground(cb);
        cleanBtn.setOnClickListener(v -> cleanRam());
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(WRAP, WRAP);
        cl.topMargin = dp(8);
        cl.bottomMargin = dp(14);
        content.addView(cleanBtn, cl);

        // dock
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setPadding(dp(8), dp(4), dp(8), dp(4));
        GradientDrawable db = grad(GradientDrawable.Orientation.TOP_BOTTOM, 0xF02A2008, 0xF0000000);
        db.setCornerRadius(dp(30));
        db.setStroke(dp(1.5f), GOLD);
        dock.setBackground(db);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(MATCH, WRAP);
        dl.setMargins(dp(14), 0, dp(14), dp(12));
        content.addView(dock, dl);

        buildDrawer();

        root.setOnApplyWindowInsetsListener((v, ins) -> {
            int top = ins.getSystemWindowInsetTop();
            int bottom = ins.getSystemWindowInsetBottom();
            content.setPadding(0, top, 0, bottom);
            drawerBox.setPadding(0, top, 0, bottom);
            return ins;
        });
    }

    private void buildDrawer() {
        drawerBox = new LinearLayout(this);
        drawerBox.setOrientation(LinearLayout.VERTICAL);
        drawerBox.setBackground(grad(GradientDrawable.Orientation.TOP_BOTTOM, 0xFA050505, 0xFA130D00));
        drawerBox.setVisibility(View.GONE);

        View handle = new View(this);
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(GOLD_DARK);
        hb.setCornerRadius(dp(2));
        handle.setBackground(hb);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(dp(44), dp(4));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        hl.topMargin = dp(10);
        drawerBox.addView(handle, hl);

        search = new EditText(this);
        search.setHint("Hledat aplikace…");
        search.setHintTextColor(0x99D4AF37);
        search.setTextColor(Color.WHITE);
        search.setTextSize(16);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_DONE);
        search.setPadding(dp(18), dp(10), dp(18), dp(10));
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(0xFF111111);
        sb.setCornerRadius(dp(24));
        sb.setStroke(dp(1), GOLD_DARK);
        search.setBackground(sb);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(MATCH, WRAP);
        sp.setMargins(dp(16), dp(10), dp(16), dp(6));
        drawerBox.addView(search, sp);

        grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setVerticalSpacing(dp(14));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setVerticalScrollBarEnabled(false);
        grid.setClipToPadding(false);
        grid.setPadding(dp(8), dp(8), dp(8), dp(8));
        adapter = new AppAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> launch(adapter.shown.get(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            appOptions(adapter.shown.get(pos));
            return true;
        });
        drawerBox.addView(grid, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                adapter.filter(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        search.setOnEditorActionListener((v, actionId, ev) -> {
            if (!adapter.shown.isEmpty()) launch(adapter.shown.get(0));
            return true;
        });

        root.addView(drawerBox, new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    // ------------------------------------------------------------------ menu aplikací

    private void openDrawer() {
        if (drawerOpen) return;
        drawerOpen = true;
        adapter.filter("");
        drawerBox.setVisibility(View.VISIBLE);
        drawerBox.setTranslationY(root.getHeight());
        drawerBox.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        drawerBox.animate().translationY(0).setDuration(220)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> drawerBox.setLayerType(View.LAYER_TYPE_NONE, null))
                .start();
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        hideKeyboard();
        drawerBox.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        drawerBox.animate().translationY(root.getHeight()).setDuration(200)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    drawerBox.setVisibility(View.GONE);
                    drawerBox.setLayerType(View.LAYER_TYPE_NONE, null);
                    search.setText("");
                    grid.setSelection(0);
                })
                .start();
    }

    private boolean gridAtTop() {
        if (grid.getChildCount() == 0) return true;
        return grid.getFirstVisiblePosition() == 0 && grid.getChildAt(0).getTop() >= grid.getPaddingTop();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    private class AppAdapter extends BaseAdapter {
        final List<AppEntry> shown = new ArrayList<>();

        void filter(String q) {
            shown.clear();
            String nq = norm(q == null ? "" : q.trim());
            for (AppEntry e : allApps) {
                if (nq.isEmpty() || norm(e.label).contains(nq)) shown.add(e);
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int i) {
            return shown.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            Cell c;
            if (cv == null) {
                LinearLayout l = new LinearLayout(HomeActivity.this);
                l.setOrientation(LinearLayout.VERTICAL);
                l.setGravity(Gravity.CENTER_HORIZONTAL);
                l.setPadding(dp(2), dp(4), dp(2), dp(4));
                c = new Cell();
                c.icon = new ImageView(HomeActivity.this);
                l.addView(c.icon, new LinearLayout.LayoutParams(iconPx, iconPx));
                c.label = new TextView(HomeActivity.this);
                c.label.setTextColor(Color.WHITE);
                c.label.setTextSize(11);
                c.label.setMaxLines(1);
                c.label.setEllipsize(TextUtils.TruncateAt.END);
                c.label.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(MATCH, WRAP);
                tl.topMargin = dp(5);
                l.addView(c.label, tl);
                l.setTag(c);
                cv = l;
            } else {
                c = (Cell) cv.getTag();
            }
            AppEntry e = shown.get(pos);
            c.label.setText(e.label);
            loadIcon(c.icon, e.pkg);
            return cv;
        }
    }

    private static String norm(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ aplikace a ikony

    private void loadApps() {
        new Thread(() -> {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
            final List<AppEntry> list = new ArrayList<>();
            for (ResolveInfo ri : ris) {
                String p = ri.activityInfo.packageName;
                if (p.equals(getPackageName())) continue;
                AppEntry e = new AppEntry();
                e.pkg = p;
                e.cn = new ComponentName(p, ri.activityInfo.name);
                e.label = String.valueOf(ri.loadLabel(pm));
                list.add(e);
            }
            final Collator col = Collator.getInstance(new Locale("cs", "CZ"));
            Collections.sort(list, (a, b) -> col.compare(a.label, b.label));
            main.post(() -> applyApps(list));
        }).start();
    }

    private void applyApps(List<AppEntry> list) {
        allApps.clear();
        allApps.addAll(list);
        appMap.clear();
        for (AppEntry e : list) {
            if (!appMap.containsKey(e.pkg)) appMap.put(e.pkg, e);
        }
        ensureDock();
        rebuildDock();
        adapter.filter(search.getText().toString());
    }

    private void loadIcon(final ImageView iv, final String pkg) {
        iv.setTag(pkg);
        Bitmap cached = iconCache.get(pkg);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setImageDrawable(null);
        iconExec.execute(() -> {
            Drawable d;
            try {
                d = pm.getApplicationIcon(pkg);
            } catch (Exception e) {
                return;
            }
            final Bitmap bm = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, iconPx, iconPx);
            d.draw(c);
            iconCache.put(pkg, bm);
            main.post(() -> {
                if (pkg.equals(iv.getTag())) iv.setImageBitmap(bm);
            });
        });
    }

    private void launch(AppEntry e) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(e.cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Exception ex) {
            toast("Aplikaci nelze otevřít");
        }
        if (drawerOpen) closeDrawer();
    }

    private void launchPkg(String pkg) {
        AppEntry e = appMap.get(pkg);
        if (e != null) launch(e);
    }

    // ------------------------------------------------------------------ dock

    private void ensureDock() {
        String saved = prefs.getString("dock", "");
        List<String> d = new ArrayList<>();
        if (!saved.isEmpty()) {
            for (String s : saved.split(",")) {
                if (appMap.containsKey(s) && !d.contains(s)) d.add(s);
            }
        }
        if (d.size() < DOCK_SIZE) {
            addIfOk(d, resolvePkg(new Intent(Intent.ACTION_DIAL)));
            addIfOk(d, resolvePkg(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))));
            addIfOk(d, resolvePkg(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))));
            addIfOk(d, resolvePkg(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)));
            addIfOk(d, "com.android.settings");
            for (AppEntry e : allApps) {
                if (d.size() >= DOCK_SIZE) break;
                addIfOk(d, e.pkg);
            }
        }
        for (int i = 0; i < DOCK_SIZE; i++) {
            dockPkgs[i] = i < d.size() ? d.get(i) : null;
        }
        saveDock();
    }

    private void addIfOk(List<String> d, String pkg) {
        if (pkg != null && d.size() < DOCK_SIZE && appMap.containsKey(pkg) && !d.contains(pkg)) d.add(pkg);
    }

    private String resolvePkg(Intent i) {
        try {
            ResolveInfo ri = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri == null || ri.activityInfo == null) return null;
            String p = ri.activityInfo.packageName;
            return "android".equals(p) ? null : p;
        } catch (Exception e) {
            return null;
        }
    }

    private void saveDock() {
        StringBuilder sb = new StringBuilder();
        for (String p : dockPkgs) {
            if (p == null) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(p);
        }
        prefs.edit().putString("dock", sb.toString()).apply();
    }

    private void rebuildDock() {
        dock.removeAllViews();
        for (int i = 0; i < DOCK_SIZE; i++) {
            final int slot = i;
            final String pkg = dockPkgs[i];
            if (pkg == null) continue;
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setPadding(dp(8), dp(10), dp(8), dp(10));
            iv.setOnClickListener(v -> launchPkg(pkg));
            iv.setOnLongClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                pickDockApp(slot);
                return true;
            });
            dock.addView(iv, new LinearLayout.LayoutParams(0, iconPx + dp(20), 1f));
            loadIcon(iv, pkg);
        }
    }

    private void pickDockApp(final int slot) {
        String[] labels = new String[allApps.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = allApps.get(i).label;
        showList("Aplikace do doku – pozice " + (slot + 1), labels,
                (d, which) -> setDock(slot, allApps.get(which).pkg));
    }

    private void setDock(int slot, String pkg) {
        for (int j = 0; j < DOCK_SIZE; j++) {
            if (j != slot && pkg.equals(dockPkgs[j])) dockPkgs[j] = dockPkgs[slot];
        }
        dockPkgs[slot] = pkg;
        saveDock();
        rebuildDock();
    }

    private void appOptions(final AppEntry e) {
        final String[] items = {"ℹ  Informace o aplikaci", "🗑  Odinstalovat", "📌  Dát do doku"};
        showList(e.label, items, (d, which) -> {
            try {
                if (which == 0) {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + e.pkg)));
                } else if (which == 1) {
                    startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + e.pkg)));
                } else {
                    String[] slots = {"Pozice 1", "Pozice 2", "Pozice 3", "Pozice 4", "Pozice 5"};
                    showList("Do doku", slots, (d2, slot) -> setDock(slot, e.pkg));
                }
            } catch (Exception ex) {
                toast("Akci nelze provést");
            }
        });
    }

    // ------------------------------------------------------------------ widgety

    private void restoreWidgets() {
        String s = prefs.getString("widgets", "");
        for (String p : s.split(",")) {
            if (p.isEmpty()) continue;
            try {
                int id = Integer.parseInt(p);
                if (awm.getAppWidgetInfo(id) == null) {
                    widgetHost.deleteAppWidgetId(id);
                    continue;
                }
                widgetIds.add(id);
                addWidgetView(id);
            } catch (Exception ignored) {
            }
        }
        saveWidgets();
    }

    private void saveWidgets() {
        StringBuilder sb = new StringBuilder();
        for (Integer id : widgetIds) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        prefs.edit().putString("widgets", sb.toString()).apply();
    }

    private void addWidgetView(int id) {
        AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
        if (info == null) return;
        AppWidgetHostView hv = widgetHost.createView(this, id, info);
        hv.setTag(id);
        int h = Math.min(Math.max(info.minHeight, dp(90)), dp(420));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, h);
        lp.bottomMargin = dp(10);
        widgetBox.addView(hv, lp);
    }

    private void commitWidget(int id) {
        widgetIds.add(id);
        saveWidgets();
        addWidgetView(id);
        pendingWidgetId = -1;
    }

    private void pickWidget() {
        final List<AppWidgetProviderInfo> list = new ArrayList<>(awm.getInstalledProviders());
        final List<String> names = new ArrayList<>();
        final Map<AppWidgetProviderInfo, String> nameOf = new HashMap<>();
        for (AppWidgetProviderInfo p : list) {
            String l = p.loadLabel(pm);
            if (l == null) l = p.provider.getPackageName();
            String app = "";
            try {
                app = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(p.provider.getPackageName(), 0)));
            } catch (Exception ignored) {
            }
            nameOf.put(p, app.isEmpty() ? l : l + "  (" + app + ")");
        }
        final Collator col = Collator.getInstance(new Locale("cs", "CZ"));
        Collections.sort(list, (a, b) -> col.compare(nameOf.get(a), nameOf.get(b)));
        for (AppWidgetProviderInfo p : list) names.add(nameOf.get(p));
        if (list.isEmpty()) {
            toast("Žádné widgety nejsou k dispozici");
            return;
        }
        showList("Přidat widget", names.toArray(new String[0]), (d, which) -> bindWidget(list.get(which)));
    }

    private void bindWidget(AppWidgetProviderInfo p) {
        int id = widgetHost.allocateAppWidgetId();
        pendingWidgetId = id;
        boolean ok = false;
        try {
            ok = awm.bindAppWidgetIdIfAllowed(id, p.provider);
        } catch (Exception ignored) {
        }
        if (ok) {
            configureOrAdd(id);
        } else {
            Intent i = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
            i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
            i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, p.provider);
            try {
                startActivityForResult(i, REQ_BIND);
            } catch (Exception e) {
                widgetHost.deleteAppWidgetId(id);
                pendingWidgetId = -1;
                toast("Widget nelze přidat");
            }
        }
    }

    private void configureOrAdd(int id) {
        AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
        if (info != null && info.configure != null) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE, null);
                return;
            } catch (Exception ignored) {
            }
        }
        commitWidget(id);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_BIND && req != REQ_CONFIGURE) return;
        int id = data != null ? data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId) : pendingWidgetId;
        if (id < 0) id = pendingWidgetId;
        if (res == RESULT_OK && id >= 0) {
            if (req == REQ_BIND) configureOrAdd(id);
            else commitWidget(id);
        } else {
            if (pendingWidgetId >= 0) widgetHost.deleteAppWidgetId(pendingWidgetId);
            pendingWidgetId = -1;
        }
    }

    private void removeWidgetDialog() {
        final List<Integer> ids = new ArrayList<>(widgetIds);
        String[] labels = new String[ids.size()];
        for (int i = 0; i < labels.length; i++) {
            AppWidgetProviderInfo info = awm.getAppWidgetInfo(ids.get(i));
            String l = info == null ? null : info.loadLabel(pm);
            labels[i] = l == null ? "Widget " + (i + 1) : l;
        }
        showList("Odebrat widget", labels, (d, which) -> {
            int id = ids.get(which);
            widgetHost.deleteAppWidgetId(id);
            widgetIds.remove(Integer.valueOf(id));
            saveWidgets();
            for (int i = 0; i < widgetBox.getChildCount(); i++) {
                View v = widgetBox.getChildAt(i);
                if (Integer.valueOf(id).equals(v.getTag())) {
                    widgetBox.removeViewAt(i);
                    break;
                }
            }
        });
    }

    // ------------------------------------------------------------------ menu domovské obrazovky

    private void item(List<String> labels, List<Runnable> acts, String label, Runnable r) {
        labels.add(label);
        acts.add(r);
    }

    private void showHomeMenu() {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        item(labels, acts, "🖼  Změnit tapetu", this::changeWallpaper);
        item(labels, acts, "✨  Zlato-černá tapeta", this::setGoldWallpaper);
        item(labels, acts, "➕  Přidat widget", this::pickWidget);
        if (!widgetIds.isEmpty()) item(labels, acts, "➖  Odebrat widget", this::removeWidgetDialog);
        item(labels, acts, "⌨  Zapnout zlatou klávesnici", () -> {
            try {
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
            } catch (Exception e) {
                toast("Nelze otevřít nastavení klávesnic");
            }
        });
        item(labels, acts, "⌨  Přepnout klávesnici", () -> main.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        }, 300));
        item(labels, acts, "⚙  Nastavení telefonu", () -> {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
            }
        });
        showList("Zlatý Launcher", labels.toArray(new String[0]), (d, which) -> acts.get(which).run());
    }

    private void changeWallpaper() {
        try {
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Změnit tapetu"));
        } catch (Exception e) {
            toast("Tapetu změníš v nastavení telefonu");
        }
    }

    private void setGoldWallpaper() {
        toast("Nastavuji tapetu…");
        new Thread(() -> {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int w = dm.widthPixels;
            int h = dm.heightPixels;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.drawColor(0xFF050505);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new RadialGradient(w * 0.5f, h * 0.26f, w * 0.95f,
                    new int[]{0x66D4AF37, 0x00D4AF37}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(new RadialGradient(w * 0.5f, h, w * 0.85f,
                    new int[]{0x44D4AF37, 0x00D4AF37}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            Paint l = new Paint(Paint.ANTI_ALIAS_FLAG);
            l.setColor(0x16D4AF37);
            l.setStrokeWidth(2f);
            int step = Math.max(40, w / 9);
            for (int x = -h; x < w; x += step) c.drawLine(x, h, x + h, 0, l);
            Paint fr = new Paint(Paint.ANTI_ALIAS_FLAG);
            fr.setStyle(Paint.Style.STROKE);
            fr.setStrokeWidth(3f);
            fr.setColor(0x66D4AF37);
            float m = w * 0.05f;
            c.drawRoundRect(m, m, w - m, h - m, 48, 48, fr);
            fr.setColor(0x26D4AF37);
            float m2 = m * 1.7f;
            c.drawRoundRect(m2, m2, w - m2, h - m2, 36, 36, fr);
            String msg;
            try {
                WallpaperManager.getInstance(this).setBitmap(bmp);
                msg = "Tapeta nastavena";
            } catch (IOException | RuntimeException e) {
                msg = "Tapetu se nepodařilo nastavit";
            }
            bmp.recycle();
            final String m3 = msg;
            main.post(() -> toast(m3));
        }).start();
    }

    // ------------------------------------------------------------------ čištění paměti

    private void cleanRam() {
        final ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        final long before = availMb(am);
        cleanBtn.setText("⏳  Čistím…");
        cleanBtn.setEnabled(false);
        final String ime = currentImePackage();
        final List<String> pkgs = new ArrayList<>();
        for (AppEntry e : allApps) pkgs.add(e.pkg);
        new Thread(() -> {
            for (String p : pkgs) {
                if (p.equals(getPackageName()) || p.equals(ime)
                        || p.equals("com.android.systemui") || p.equals("com.android.phone")) continue;
                try {
                    am.killBackgroundProcesses(p);
                } catch (Exception ignored) {
                }
            }
            try {
                Thread.sleep(900);
            } catch (InterruptedException ignored) {
            }
            final long after = availMb(am);
            final long freed = Math.max(0, after - before);
            main.post(() -> {
                cleanBtn.setText("⚡  Vyčistit paměť");
                cleanBtn.setEnabled(true);
                toast("Hotovo – uvolněno ~" + freed + " MB, volno " + after + " MB");
            });
        }).start();
    }

    private long availMb(ActivityManager am) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem / (1024 * 1024);
    }

    private String currentImePackage() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (s == null) return "";
        int i = s.indexOf('/');
        return i > 0 ? s.substring(0, i) : s;
    }

    // ------------------------------------------------------------------ pomocné

    private boolean inView(View v, MotionEvent e) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        float x = e.getRawX();
        float y = e.getRawY();
        return x >= loc[0] && x <= loc[0] + v.getWidth() && y >= loc[1] && y <= loc[1] + v.getHeight();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void showList(String title, String[] items, DialogInterface.OnClickListener l) {
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setItems(items, l)
                .create();
        d.show();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0B0B0B);
        bg.setCornerRadius(dp(24));
        bg.setStroke(dp(1.5f), GOLD);
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(bg);
        int tid = getResources().getIdentifier("alertTitle", "id", "android");
        View t = d.findViewById(tid);
        if (t instanceof TextView) ((TextView) t).setTextColor(GOLD);
    }
}
