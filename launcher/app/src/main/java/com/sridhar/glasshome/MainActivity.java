package com.sridhar.glasshome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Apple TV style home screen. Focus is not Android view focus: the activity keeps a cursor
 * (zone + index) and moves it itself, so D-pad navigation is fully deterministic.
 */
public class MainActivity extends Activity {

    private static final int TOP = 0, DOCK = 1, GRID = 2, MENU = 3, NOW = 4;
    private static final int COLS = 4;
    private static final int PILL_INPUTS = 0, PILL_WIFI = 1, PILL_SETTINGS = 2;

    // Layout, in dp, on a 960 x 540 dp screen.
    private static final int SIDE = 40;           // dock band / grid left edge
    private static final int CELL = 220;          // column pitch, shared by dock and grid
    private static final int BAND_TOP = 364, BAND_H = 156;
    private static final int GRID_SHIFT = BAND_TOP - 28; // page offset in grid mode

    private float density;
    private int screenW, screenH;
    private Tile.Metrics metrics;
    private Typeface thin, light, regular, medium;

    private FrameLayout root, page, home;
    private RecyclerView grid;
    private GlassHighlight glass;
    private TextView greeting, heroName, wifiPill;
    private View glow, dockBand;
    private GradientDrawable glowDrawable;
    private final TextView[] pills = new TextView[3];
    private final List<Tile> dockTiles = new ArrayList<>();
    private LinearLayout menuPanel;
    private final List<TextView> menuRows = new ArrayList<>();
    private final List<Runnable> menuActions = new ArrayList<>();

    private final List<AppEntry> dockApps = new ArrayList<>();
    private final List<AppEntry> gridApps = new ArrayList<>();
    private GridAdapter adapter;

    private int zone = DOCK, topIdx = PILL_INPUTS, dockIdx = 0, gridIdx = 0, menuIdx = 0;
    private int firstRow = 0;
    private boolean inGrid, appsDirty, laidOut;
    private int glowColor = 0xFF5A6CFF;
    private ValueAnimator glowAnim;
    private SharedPreferences prefs;

    // Move mode: holding OK on an app picks it up; arrows move it; OK drops, Back cancels.
    private boolean moving, okLongPressed;
    private List<AppEntry> movedDock, movedGrid;
    private TextView moveHint;

    // Now Playing card, between the top bar and the dock when something is playing.
    private NowPlaying nowPlaying;
    private int nowIdx = NowPlaying.PLAY;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        metrics = new Tile.Metrics(density, CELL);
        prefs = getSharedPreferences("state", MODE_PRIVATE);
        loadFonts();
        buildUi();
        loadApps();
        restoreState();
        registerReceivers();
        nowPlaying.start();
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (!laidOut && root.getWidth() > 0) {
                laidOut = true;
                applyFocus(false);
                glass.setTarget(this::focusRect, true);
            } else if (laidOut) {
                glass.kick(); // targets move when the grid or dock re-lays out (e.g. move mode)
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appsDirty) {
            appsDirty = false;
            loadApps();
        }
        updateWifi();
        updateGreeting();
        nowPlaying.start();
        if (laidOut) glass.setTarget(this::focusRect, true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while we are already in front: go back to the dock.
        if (moving) endMove(false);
        if (zone == MENU) closeMenu();
        if (!dockApps.isEmpty()) moveTo(DOCK, dockIdx);
        else moveTo(TOP, topIdx);
    }

    @Override
    protected void onDestroy() {
        nowPlaying.stop();
        try { unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) { }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI

    private int dp(float v) { return Math.round(v * density); }

    private void loadFonts() {
        thin = font(200);
        light = font(300);
        regular = font(400);
        medium = font(500);
    }

    private Typeface font(int weight) {
        try {
            return new Typeface.Builder(getAssets(), "fonts/Outfit.ttf")
                    .setFontVariationSettings("'wght' " + weight).build();
        } catch (Exception e) {
            return Typeface.create("sans-serif-light", Typeface.NORMAL);
        }
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.setClipChildren(false);
        setContentView(root);

        if (getResources().getBoolean(R.bool.use_wallpaper)) {
            int id = getResources().getIdentifier("wallpaper", "drawable", getPackageName());
            if (id != 0) {
                ImageView wp = new ImageView(this);
                wp.setScaleType(ImageView.ScaleType.CENTER_CROP);
                wp.setImageResource(id);
                root.addView(wp, new FrameLayout.LayoutParams(-1, -1));
                View dim = new View(this);
                dim.setBackgroundColor(0x80000000);
                root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
            }
        }

        int gridH = screenH - (dp(BAND_TOP) - dp(GRID_SHIFT)) - dp(BAND_H) - dp(8);
        page = new FrameLayout(this);
        page.setClipChildren(false);
        root.addView(page, new FrameLayout.LayoutParams(screenW, screenH + gridH));

        home = new FrameLayout(this);
        home.setClipChildren(false);
        page.addView(home, new FrameLayout.LayoutParams(screenW, screenH));

        buildGlow();
        buildTopBar();
        nowPlaying = new NowPlaying(this, regular, medium, this::onNowPlayingChanged);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(dp(362), dp(126), Gravity.END | Gravity.TOP);
        nlp.topMargin = dp(104);
        nlp.rightMargin = dp(56);
        home.addView(nowPlaying.card, nlp);
        buildHero();
        buildDock();
        buildGrid(gridH);
        buildMenu();

        glass = new GlassHighlight(this);
        root.addView(glass, new FrameLayout.LayoutParams(-1, -1));

        moveHint = text(regular, 14, 0xFFF2F2F7);
        moveHint.setText("Moving  ·  arrows to move  ·  OK to drop  ·  Back to cancel");
        moveHint.setGravity(Gravity.CENTER);
        moveHint.setPadding(dp(22), 0, dp(22), 0);
        GradientDrawable hb = new GradientDrawable();
        hb.setCornerRadius(dp(16));
        hb.setColor(0xE61C1C1F);
        hb.setStroke(Math.max(1, dp(1)), 0x33FFFFFF);
        moveHint.setBackground(hb);
        moveHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, dp(32), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hlp.topMargin = dp(6); // clear of the top-bar pills at 44dp
        root.addView(moveHint, hlp);
    }

    private FrameLayout.LayoutParams at(int w, int h, int xdp, int ydp) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = dp(xdp);
        lp.topMargin = dp(ydp);
        return lp;
    }

    private void buildGlow() {
        glowDrawable = new GradientDrawable();
        glowDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowDrawable.setGradientRadius(dp(420));
        glowDrawable.setGradientCenter(0.32f, 0.62f);
        glow = new View(this);
        glow.setBackground(glowDrawable);
        home.addView(glow, at(dp(1100), dp(560), -220, 40));
        setGlow(glowColor);
    }

    private void setGlow(int c) {
        glowColor = c;
        int rgb = c & 0x00FFFFFF;
        glowDrawable.setColors(new int[]{0x40000000 | rgb, 0x14000000 | rgb, 0x00000000});
        if (heroName != null) heroName.setShadowLayer(dp(26), 0, 0, 0xB0000000 | rgb);
    }

    private TextView text(Typeface face, float sp, int color) {
        TextView t = new TextView(this);
        t.setTypeface(face);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine();
        return t;
    }

    private void buildTopBar() {
        greeting = text(regular, 16, 0xFF8E8E93);
        home.addView(greeting, at(-2, -2, 58, 40));

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTypeface(thin);
        clock.setTextSize(76);
        clock.setTextColor(0xFFFFFFFF);
        clock.setIncludeFontPadding(false);
        clock.setLetterSpacing(-0.02f);
        home.addView(clock, at(-2, -2, 54, 62));

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTypeface(regular);
        date.setTextSize(16);
        date.setTextColor(0xFFA1A1A6);
        date.setIncludeFontPadding(false);
        home.addView(date, at(-2, -2, 58, 150));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(-2, dp(40), Gravity.END | Gravity.TOP);
        rlp.topMargin = dp(44);
        rlp.rightMargin = dp(56);
        home.addView(row, rlp);
        String[] names = {"Inputs", "Wi-Fi", "Settings"};
        for (int i = 0; i < 3; i++) {
            TextView p = text(regular, 15, 0xFFEDEDF0);
            p.setText(names[i]);
            p.setGravity(Gravity.CENTER);
            p.setPadding(dp(20), 0, dp(20), 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(20));
            bg.setColor(0x1FFFFFFF);
            bg.setStroke(Math.max(1, dp(1)), 0x2BFFFFFF);
            p.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -1);
            if (i > 0) lp.leftMargin = dp(12);
            row.addView(p, lp);
            pills[i] = p;
        }
        wifiPill = pills[PILL_WIFI];
    }

    private void buildHero() {
        heroName = text(light, 50, 0xFFFFFFFF);
        heroName.setLetterSpacing(-0.01f);
        heroName.setEllipsize(TextUtils.TruncateAt.END);
        heroName.setShadowLayer(dp(26), 0, 0, 0xB05A6CFF);
        heroName.setPadding(dp(20), dp(20), dp(20), dp(20)); // room for the glow
        home.addView(heroName, at(dp(860), -2, 38, 252));
    }

    private void buildDock() {
        dockBand = new View(this);
        GradientDrawable band = new GradientDrawable();
        band.setCornerRadius(dp(30));
        band.setColor(0xFF111113);
        band.setStroke(Math.max(1, dp(1)), 0xFF1D1D21);
        dockBand.setBackground(band);
        home.addView(dockBand, at(screenW - 2 * dp(SIDE), dp(BAND_H), SIDE, BAND_TOP));
    }

    private void rebuildDockTiles() {
        for (Tile t : dockTiles) home.removeView(t.root);
        dockTiles.clear();
        for (int i = 0; i < dockApps.size() && i < COLS; i++) {
            Tile t = new Tile(this, metrics, medium);
            t.bind(dockApps.get(i));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) t.root.getLayoutParams();
            lp.leftMargin = dp(SIDE) + i * metrics.cellW;
            lp.topMargin = dp(BAND_TOP + 14) - metrics.cardTop;
            t.root.setAlpha(inGrid ? 0.45f : 1f);
            home.addView(t.root, lp);
            dockTiles.add(t);
        }
    }

    private void buildGrid(int gridH) {
        grid = new RecyclerView(this);
        // Clip to the grid's own bounds so scrolled rows never draw over the dock.
        // Each cell already has room for the focused tile's scale-up.
        grid.setClipToPadding(true);
        grid.setClipChildren(true);
        grid.setFocusable(false);
        grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        grid.setItemAnimator(null);
        grid.setHasFixedSize(true);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setPadding(dp(SIDE), 0, dp(SIDE), dp(40));
        GridLayoutManager lm = new GridLayoutManager(this, COLS) {
            @Override
            protected void calculateExtraLayoutSpace(@NonNull RecyclerView.State s, @NonNull int[] extra) {
                extra[0] = metrics.cellH;
                extra[1] = metrics.cellH;
            }
        };
        grid.setLayoutManager(lm);
        adapter = new GridAdapter();
        grid.setAdapter(adapter);
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {

                glass.kick();
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(screenW, gridH);
        lp.topMargin = screenH;
        page.addView(grid, lp);
    }

    private void buildMenu() {
        menuPanel = new LinearLayout(this);
        menuPanel.setOrientation(LinearLayout.VERTICAL);
        menuPanel.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(0xF21C1C1F);
        bg.setStroke(Math.max(1, dp(1)), 0x24FFFFFF);
        menuPanel.setBackground(bg);
        menuPanel.setVisibility(View.GONE);
        root.addView(menuPanel, new FrameLayout.LayoutParams(dp(230), -2));
    }

    // ---------------------------------------------------------------- data

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Set<String> hidden = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.hidden_packages)));
        hidden.add(getPackageName());
        Map<String, AppEntry> all = new LinkedHashMap<>();
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, true, hidden, all);
        collect(pm, Intent.CATEGORY_LAUNCHER, false, hidden, all);

        String keepGrid = gridIdx < gridApps.size() ? gridApps.get(gridIdx).pkg : prefs.getString("grid", null);
        // An order arranged on the TV (move mode) wins over config.xml.
        String savedDock = prefs.getString("dock_order", null);
        String[] dockPkgs = savedDock != null ? splitPkgs(savedDock)
                : getResources().getStringArray(R.array.dock_packages);
        dockApps.clear();
        for (String p : dockPkgs) {
            AppEntry e = all.remove(p);
            if (e != null && dockApps.size() < COLS) dockApps.add(e);
        }
        gridApps.clear();
        gridApps.addAll(all.values());
        // Saved grid order first; apps not in it (new installs) follow alphabetically.
        List<String> savedGrid = Arrays.asList(splitPkgs(prefs.getString("grid_order", "")));
        Collections.sort(gridApps, (a, b) -> {
            int ia = savedGrid.indexOf(a.pkg), ib = savedGrid.indexOf(b.pkg);
            if (ia < 0) ia = Integer.MAX_VALUE;
            if (ib < 0) ib = Integer.MAX_VALUE;
            return ia != ib ? Integer.compare(ia, ib) : a.label.compareToIgnoreCase(b.label);
        });

        gridIdx = 0;
        for (int i = 0; i < gridApps.size(); i++) if (gridApps.get(i).pkg.equals(keepGrid)) gridIdx = i;
        dockIdx = Math.min(dockIdx, Math.max(0, dockApps.size() - 1));
        rebuildDockTiles();
        adapter.notifyDataSetChanged();
        if (zone == GRID && gridApps.isEmpty()) zone = DOCK;
        if (zone == DOCK && dockApps.isEmpty()) zone = TOP;
        if (laidOut) {
            applyFocus(false);
            glass.setTarget(this::focusRect, true);
        }
    }

    private void collect(PackageManager pm, String category, boolean leanback, Set<String> hidden,
                         Map<String, AppEntry> out) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (out.containsKey(pkg) || hidden.contains(pkg)) continue;
            ComponentName cn = new ComponentName(pkg, ri.activityInfo.name);
            Drawable banner = null;
            try { banner = pm.getActivityBanner(cn); } catch (Exception ignored) { }
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Drawable icon = ri.loadIcon(pm);
            Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(category).setComponent(cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            String label = String.valueOf(ri.loadLabel(pm));
            out.put(pkg, new AppEntry(pkg, label, leanback ? banner : null, icon, launch));
        }
    }

    private static String[] splitPkgs(String s) {
        return s == null || s.isEmpty() ? new String[0] : s.split(",");
    }

    private void saveOrder() {
        StringBuilder d = new StringBuilder(), g = new StringBuilder();
        for (AppEntry e : dockApps) d.append(d.length() > 0 ? "," : "").append(e.pkg);
        for (AppEntry e : gridApps) g.append(g.length() > 0 ? "," : "").append(e.pkg);
        prefs.edit().putString("dock_order", d.toString()).putString("grid_order", g.toString()).apply();
    }

    private void restoreState() {
        dockIdx = Math.min(prefs.getInt("dock", 0), Math.max(0, dockApps.size() - 1));
        zone = dockApps.isEmpty() ? TOP : DOCK;
    }

    private void saveState() {
        SharedPreferences.Editor e = prefs.edit().putInt("dock", dockIdx);
        if (gridIdx < gridApps.size()) e.putString("grid", gridApps.get(gridIdx).pkg);
        e.apply();
    }

    // ---------------------------------------------------------------- focus model

    private int rows() { return (gridApps.size() + COLS - 1) / COLS; }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        int k = ev.getKeyCode();
        boolean handled;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
                handled = true;
                break;
            default:
                return super.dispatchKeyEvent(ev);
        }
        boolean first = ev.getRepeatCount() == 0;
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            // OK acts on release, so that holding it can start move mode instead.
            if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                if (first) okLongPressed = false;
                else if (!okLongPressed) { okLongPressed = true; okLongPress(); }
            } else if (ev.getAction() == KeyEvent.ACTION_UP) {
                if (!okLongPressed && !ev.isCanceled()) {
                    if (moving) endMove(true); else select();
                }
                okLongPressed = false;
            }
            return true;
        }
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return handled;
        if (moving) {
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_UP: moveHeld(0, -1); break;
                case KeyEvent.KEYCODE_DPAD_DOWN: moveHeld(0, 1); break;
                case KeyEvent.KEYCODE_DPAD_LEFT: moveHeld(-1, 0); break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: moveHeld(1, 0); break;
                case KeyEvent.KEYCODE_BACK: if (first) endMove(false); break;
                default: break;
            }
            return true;
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: up(); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: down(); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: horizontal(-1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: horizontal(1); break;
            case KeyEvent.KEYCODE_BACK: if (first) back(); break;
            case KeyEvent.KEYCODE_MENU: if (first) appInfo(); break;
            default: break;
        }
        return true;
    }

    // ---------------------------------------------------------------- move mode

    private void onNowPlayingChanged() {
        if (!nowPlaying.isShown() && zone == NOW) {
            if (!dockApps.isEmpty()) moveTo(DOCK, dockIdx); else moveTo(TOP, topIdx);
        } else if (laidOut) {
            glass.kick();
        }
    }

    private void okLongPress() {
        if (zone == DOCK || zone == GRID) startMove();
        else select(); // nothing to move in the top bar or the inputs menu
    }

    private void startMove() {
        moving = true;
        movedDock = new ArrayList<>(dockApps);
        movedGrid = new ArrayList<>(gridApps);
        glass.setLifted(true);
        moveHint.setVisibility(View.VISIBLE);
        moveHint.setAlpha(0f);
        moveHint.animate().alpha(1f).setDuration(180).start();
    }

    /** Drop (keep = true, saves the order) or cancel (restores the order from before the move). */
    private void endMove(boolean keep) {
        moving = false;
        glass.setLifted(false);
        moveHint.animate().alpha(0f).setDuration(150).withEndAction(() -> moveHint.setVisibility(View.GONE)).start();
        if (keep) {
            saveOrder();
            return;
        }
        AppEntry held = zone == DOCK ? dockApps.get(dockIdx) : gridApps.get(gridIdx);
        dockApps.clear(); dockApps.addAll(movedDock);
        gridApps.clear(); gridApps.addAll(movedGrid);
        int d = dockApps.indexOf(held);
        refreshAfterMove(d >= 0 ? DOCK : GRID, d >= 0 ? d : gridApps.indexOf(held));
    }

    /** Move the held app one step: dx is left/right, dy is up/down. */
    private void moveHeld(int dx, int dy) {
        if (zone == DOCK) {
            AppEntry e = dockApps.get(dockIdx);
            if (dx != 0) {
                int n = dockIdx + dx;
                if (n < 0 || n >= dockApps.size()) return;
                Collections.swap(dockApps, dockIdx, n);
                refreshAfterMove(DOCK, n);
            } else if (dy > 0 && dockApps.size() > 1) {
                // Out of the dock: into the first grid row, under where it was.
                dockApps.remove(dockIdx);
                int n = Math.min(dockIdx, gridApps.size());
                gridApps.add(n, e);
                refreshAfterMove(GRID, n);
            }
        } else if (zone == GRID) {
            AppEntry e = gridApps.get(gridIdx);
            int col = gridIdx % COLS;
            if (dy < 0 && gridIdx < COLS) {
                // Into the dock. A full dock swaps: its app takes this grid spot.
                int n = Math.min(col, dockApps.size());
                if (dockApps.size() < COLS) {
                    gridApps.remove(gridIdx);
                    dockApps.add(n, e);
                } else {
                    gridApps.set(gridIdx, dockApps.get(n));
                    dockApps.set(n, e);
                }
                refreshAfterMove(DOCK, n);
                return;
            }
            int n = dx != 0 ? gridIdx + dx : gridIdx + dy * COLS;
            n = Math.max(0, Math.min(n, gridApps.size() - 1));
            if (n == gridIdx) return;
            gridApps.remove(gridIdx);
            gridApps.add(n, e);
            refreshAfterMove(GRID, n);
        }
    }

    private void refreshAfterMove(int newZone, int idx) {
        dockIdx = Math.max(0, Math.min(dockIdx, dockApps.size() - 1));
        rebuildDockTiles();
        adapter.notifyDataSetChanged();
        moveTo(newZone, idx);
        applyFocus(false);
        updateHero(false);
    }

    private void up() {
        switch (zone) {
            case MENU: if (menuIdx > 0) moveTo(MENU, menuIdx - 1); break;
            case DOCK: if (nowPlaying.isShown()) moveTo(NOW, nowIdx); else moveTo(TOP, topIdx); break;
            case NOW: moveTo(TOP, topIdx); break;
            case GRID: {
                int row = gridIdx / COLS, col = gridIdx % COLS;
                if (row == 0) moveTo(dockApps.isEmpty() ? TOP : DOCK, Math.min(col, dockApps.size() - 1));
                else moveTo(GRID, gridIdx - COLS);
                break;
            }
            default: break; // nothing above the top bar
        }
    }

    private void down() {
        switch (zone) {
            case MENU: if (menuIdx < menuRows.size() - 1) moveTo(MENU, menuIdx + 1); break;
            case TOP:
                if (nowPlaying.isShown()) moveTo(NOW, nowIdx);
                else if (!dockApps.isEmpty()) moveTo(DOCK, dockIdx);
                else if (!gridApps.isEmpty()) moveTo(GRID, 0);
                break;
            case NOW:
                if (!dockApps.isEmpty()) moveTo(DOCK, dockIdx);
                else if (!gridApps.isEmpty()) moveTo(GRID, 0);
                break;
            case DOCK:
                if (!gridApps.isEmpty()) moveTo(GRID, Math.min(dockIdx, gridApps.size() - 1));
                break;
            case GRID: {
                int row = gridIdx / COLS, col = gridIdx % COLS;
                if (row + 1 < rows()) moveTo(GRID, Math.min((row + 1) * COLS + col, gridApps.size() - 1));
                break;
            }
            default: break;
        }
    }

    private void horizontal(int d) {
        switch (zone) {
            case TOP: {
                int n = topIdx + d;
                if (n >= 0 && n < pills.length) moveTo(TOP, n); // ends don't fall through
                break;
            }
            case DOCK: {
                int n = dockIdx + d;
                if (n >= 0 && n < dockApps.size()) moveTo(DOCK, n);
                break;
            }
            case GRID: {
                int col = gridIdx % COLS, n = gridIdx + d;
                if (col + d >= 0 && col + d < COLS && n < gridApps.size()) moveTo(GRID, n);
                break;
            }
            case MENU: if (d < 0) closeMenu(); break;
            case NOW: {
                int n = nowIdx + d;
                if (n >= 0 && n < nowPlaying.buttons.length) moveTo(NOW, n);
                break;
            }
            default: break;
        }
    }

    private void back() {
        if (zone == MENU) closeMenu();
        else if (zone == GRID) moveTo(dockApps.isEmpty() ? TOP : DOCK, dockIdx);
        else if ((zone == TOP || zone == NOW) && !dockApps.isEmpty()) moveTo(DOCK, dockIdx);
    }

    private void select() {
        switch (zone) {
            case DOCK: launch(dockApps.get(dockIdx)); break;
            case GRID: launch(gridApps.get(gridIdx)); break;
            case NOW: nowPlaying.press(nowIdx); break;
            case MENU: {
                Runnable r = menuActions.get(menuIdx);
                closeMenu();
                r.run();
                break;
            }
            case TOP:
                if (topIdx == PILL_INPUTS) openMenu();
                else if (topIdx == PILL_WIFI) start(new Intent(Settings.ACTION_WIFI_SETTINGS));
                else start(new Intent(Settings.ACTION_SETTINGS));
                break;
            default: break;
        }
    }

    private void appInfo() {
        AppEntry e = zone == DOCK ? dockApps.get(dockIdx) : zone == GRID ? gridApps.get(gridIdx) : null;
        if (e != null) start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg)));
    }

    private void moveTo(int newZone, int idx) {
        int oldZone = zone;
        clearFocusVisual();
        zone = newZone;
        switch (newZone) {
            case TOP: topIdx = idx; break;
            case DOCK: dockIdx = idx; break;
            case GRID: gridIdx = idx; break;
            case MENU: menuIdx = idx; break;
            case NOW: nowIdx = idx; break;
        }
        if (newZone == GRID) scrollGridToRow(gridIdx / COLS);
        setGridMode(newZone == GRID);
        applyFocus(true);
        if (newZone == DOCK) updateHero(oldZone == DOCK || !inGrid);
        glass.setTarget(this::focusRect, false);
        saveState();
    }

    private void clearFocusVisual() {
        if (zone == DOCK && dockIdx < dockTiles.size()) dockTiles.get(dockIdx).setFocused(false, true);
        if (zone == GRID) {
            RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(gridIdx);
            if (h != null) ((Holder) h).tile.setFocused(false, true);
        }
        if (zone == TOP) pills[topIdx].setTextColor(0xFFEDEDF0);
        if (zone == MENU && menuIdx < menuRows.size()) menuRows.get(menuIdx).setTextColor(0xFFD8D8DC);
    }

    private void applyFocus(boolean animate) {
        for (int i = 0; i < dockTiles.size(); i++) dockTiles.get(i).setFocused(zone == DOCK && i == dockIdx, animate);
        for (int i = 0; i < grid.getChildCount(); i++) {
            Holder h = (Holder) grid.getChildViewHolder(grid.getChildAt(i));
            h.tile.setFocused(zone == GRID && h.getBindingAdapterPosition() == gridIdx, animate);
        }
        for (int i = 0; i < pills.length; i++) pills[i].setTextColor(zone == TOP && i == topIdx ? 0xFFFFFFFF : 0xFFEDEDF0);
        for (int i = 0; i < menuRows.size(); i++) menuRows.get(i).setTextColor(zone == MENU && i == menuIdx ? 0xFFFFFFFF : 0xFFD8D8DC);
        if (!animate) {
            setGridMode(zone == GRID);
            updateHero(false);
        }
    }

    private void setGridMode(boolean g) {
        if (g == inGrid && laidOut) return;
        inGrid = g;
        DecelerateInterpolator di = new DecelerateInterpolator(2.2f);
        page.animate().translationY(g ? -dp(GRID_SHIFT) : 0).setDuration(420).setInterpolator(di)
                .setUpdateListener(a -> glass.kick()).start();
        heroName.animate().alpha(g ? 0f : 1f).setDuration(250).start();
        glow.animate().alpha(g ? 0f : 1f).setDuration(300).start();
        dockBand.animate().alpha(g ? 0.55f : 1f).setDuration(300).start();
        for (Tile t : dockTiles) t.root.animate().alpha(g ? 0.45f : 1f).setDuration(300).start();
    }

    private void scrollGridToRow(int row) {
        int visible = Math.max(1, (grid.getHeight() - grid.getPaddingTop()) / metrics.cellH);
        // Read the real scroll position every time; a running total drifts when the app list
        // reloads or the grid clamps its scroll, and then the screen stops following the cursor.
        int offset = gridOffset();
        firstRow = Math.round(offset / (float) metrics.cellH);
        if (row < firstRow) firstRow = row;
        else if (row > firstRow + visible - 1) firstRow = row - visible + 1;
        int dy = firstRow * metrics.cellH - offset;
        if (dy != 0) grid.smoothScrollBy(0, dy, new DecelerateInterpolator(2f));
    }

    /** Current vertical scroll of the grid in px, derived from the first laid-out child. */
    private int gridOffset() {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View c = grid.getChildAt(i);
            int pos = grid.getChildLayoutPosition(c); // still valid right after a data change
            if (pos != RecyclerView.NO_POSITION) {
                return (pos / COLS) * metrics.cellH - (c.getTop() - grid.getPaddingTop());
            }
        }
        return 0;
    }

    private void updateHero(boolean animate) {
        if (dockApps.isEmpty()) { heroName.setText(""); return; }
        AppEntry e = dockApps.get(dockIdx);
        if (!e.label.contentEquals(heroName.getText())) {
            heroName.setText(e.label);
            if (animate && !inGrid) {
                heroName.setAlpha(0f);
                heroName.setTranslationY(dp(6));
                heroName.animate().alpha(1f).translationY(0).setDuration(260).start();
            }
        }
        int to = e.glowColor();
        if (glowAnim != null) glowAnim.cancel();
        if (!animate) { setGlow(to); return; }
        glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), glowColor, to);
        glowAnim.setDuration(350);
        glowAnim.addUpdateListener(a -> setGlow((Integer) a.getAnimatedValue()));
        glowAnim.start();
    }

    /** Target rectangle for the glass highlight, in root coordinates. Returns the corner radius. */
    private float focusRect(RectF out) {
        View v;
        switch (zone) {
            case TOP:
                v = pills[topIdx];
                rectIn(v, out);
                out.inset(-dp(3), -dp(3));
                return out.height() / 2f;
            case NOW:
                rectIn(nowPlaying.buttons[nowIdx], out);
                out.inset(-dp(4), -dp(4));
                return out.height() / 2f;
            case MENU:
                if (menuIdx >= menuRows.size()) return -1;
                rectIn(menuRows.get(menuIdx), out);
                return dp(12);
            case DOCK:
                if (dockIdx >= dockTiles.size()) return -1;
                v = dockTiles.get(dockIdx).card;
                break;
            case GRID: {
                RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(gridIdx);
                if (h == null) return -1;
                v = ((Holder) h).tile.card;
                break;
            }
            default: return -1;
        }
        rectIn(v, out);
        float cx = out.centerX(), cy = out.centerY();
        float hw = out.width() * Tile.FOCUS_SCALE / 2f + dp(5), hh = out.height() * Tile.FOCUS_SCALE / 2f + dp(5);
        out.set(cx - hw, cy - hh, cx + hw, cy + hh);
        return metrics.tileRadius * Tile.FOCUS_SCALE + dp(5);
    }

    /** Unscaled bounds of v in root coordinates, following parent translations and scrolls. */
    private void rectIn(View v, RectF out) {
        float x = 0, y = 0;
        View cur = v;
        while (cur != null && cur != root) {
            x += cur.getLeft() + cur.getTranslationX();
            y += cur.getTop() + cur.getTranslationY();
            if (!(cur.getParent() instanceof View)) break;
            View parent = (View) cur.getParent();
            x -= parent.getScrollX();
            y -= parent.getScrollY();
            cur = parent;
        }
        out.set(x, y, x + v.getWidth(), y + v.getHeight());
    }

    // ---------------------------------------------------------------- inputs menu

    private void openMenu() {
        menuRows.clear();
        menuActions.clear();
        menuPanel.removeAllViews();
        List<TvInputInfo> hdmi = new ArrayList<>(), other = new ArrayList<>();
        TvInputInfo tuner = null;
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isHidden(this)) continue;
                if (info.isPassthroughInput()) {
                    if (info.getType() == TvInputInfo.TYPE_HDMI) hdmi.add(info); else other.add(info);
                } else if (info.getType() == TvInputInfo.TYPE_TUNER && info.getId().contains("/HW") && tuner == null) {
                    tuner = info; // the built-in tuner, not an app's virtual channel input
                }
            }
        }
        Collections.sort(hdmi, (a, b) -> Integer.compare(portNumber(label(a)), portNumber(label(b))));
        for (TvInputInfo i : hdmi) addMenuRow(label(i), () -> tune(i, true));
        for (TvInputInfo i : other) addMenuRow(label(i), () -> tune(i, true));
        final TvInputInfo t = tuner;
        addMenuRow("Live TV", () -> {
            if (t != null) tune(t, false);
            else launchPackage("com.tcl.tv");
        });

        RectF r = new RectF();
        rectIn(pills[PILL_INPUTS], r);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menuPanel.getLayoutParams();
        lp.leftMargin = Math.round(Math.min(r.left, screenW - dp(230) - dp(24)));
        lp.topMargin = Math.round(r.bottom + dp(12));
        menuPanel.setLayoutParams(lp);
        menuPanel.setAlpha(0f);
        menuPanel.setTranslationY(-dp(8));
        menuPanel.setVisibility(View.VISIBLE);
        menuPanel.animate().alpha(1f).translationY(0).setDuration(180).start();
        // Wait for the rows to be laid out before the highlight jumps into the menu.
        menuPanel.post(() -> moveTo(MENU, 0));
    }

    private String label(TvInputInfo i) {
        CharSequence c = i.loadCustomLabel(this);
        if (TextUtils.isEmpty(c)) c = i.loadLabel(this);
        // TCL mixes "HDMI 1" and "HDMI2"; show them all as "HDMI 2".
        return String.valueOf(c).trim().replaceAll("^([A-Za-z]+)(\\d)", "$1 $2");
    }

    private static int portNumber(String label) {
        String digits = label.replaceAll("\\D+", "");
        return digits.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(digits);
    }

    private void addMenuRow(String text, Runnable action) {
        TextView t = text(regular, 16, 0xFFD8D8DC);
        t.setText(text);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(16), 0, dp(16), 0);
        menuPanel.addView(t, new LinearLayout.LayoutParams(-1, dp(44)));
        menuRows.add(t);
        menuActions.add(action);
    }

    private void closeMenu() {
        menuPanel.setVisibility(View.GONE);
        moveTo(TOP, PILL_INPUTS);
    }

    private void tune(TvInputInfo info, boolean passthrough) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        if (passthrough) {
            i.setDataAndType(TvContract.buildChannelUriForPassthroughInput(info.getId()), TvContract.Channels.CONTENT_ITEM_TYPE);
        } else {
            i.setDataAndType(TvContract.buildChannelsUriForInput(info.getId()), TvContract.Channels.CONTENT_TYPE);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!start(i)) launchPackage("com.tcl.tv");
    }

    // ---------------------------------------------------------------- launching

    private void launch(AppEntry e) { start(e.launch); }

    private void launchPackage(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) start(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private boolean start(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- status

    private void updateGreeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g = h < 5 ? "Good evening" : h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening";
        String name = getString(R.string.greeting_name).trim();
        greeting.setText(name.isEmpty() ? g : g + ", " + name);
    }

    @SuppressWarnings("deprecation")
    private void updateWifi() {
        String ssid = null;
        boolean ethernet = false;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
        if (ni != null && ni.isConnected()) {
            if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) ethernet = true;
            else if (ni.getType() == ConnectivityManager.TYPE_WIFI) ssid = cleanSsid(ni.getExtraInfo());
        }
        if (ssid == null && !ethernet) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm != null ? wm.getConnectionInfo() : null;
            if (wi != null) ssid = cleanSsid(wi.getSSID());
        }
        String text = ethernet ? "Ethernet" : ssid != null ? "Wi-Fi  ·  " + ssid
                : (ni != null && ni.isConnected()) ? "Wi-Fi" : "Not connected";
        wifiPill.setText(text);
        if (zone == TOP && laidOut) glass.kick();
    }

    private static String cleanSsid(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) s = s.substring(1, s.length() - 1);
        if (s.isEmpty() || s.contains("<unknown")) return null;
        return s;
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = String.valueOf(i.getAction());
            if (a.startsWith("android.intent.action.PACKAGE_")) {
                if (hasWindowFocus()) loadApps(); else appsDirty = true;
            } else if (Intent.ACTION_TIME_TICK.equals(a) || Intent.ACTION_TIME_CHANGED.equals(a)) {
                updateGreeting();
            } else {
                updateWifi();
            }
        }
    };

    @SuppressWarnings("deprecation")
    private void registerReceivers() {
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(receiver, pf);
        IntentFilter sf = new IntentFilter();
        sf.addAction(Intent.ACTION_TIME_TICK);
        sf.addAction(Intent.ACTION_TIME_CHANGED);
        sf.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        sf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(receiver, sf);
    }

    // ---------------------------------------------------------------- grid adapter

    private static final class Holder extends RecyclerView.ViewHolder {
        final Tile tile;
        Holder(Tile t) { super(t.root); tile = t; }
    }

    private final class GridAdapter extends RecyclerView.Adapter<Holder> {
        GridAdapter() { setHasStableIds(true); }

        @Override public long getItemId(int position) { return gridApps.get(position).pkg.hashCode(); }

        @Override public int getItemCount() { return gridApps.size(); }

        @NonNull @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Tile t = new Tile(MainActivity.this, metrics, medium);
            t.root.setLayoutParams(new RecyclerView.LayoutParams(metrics.cellW, metrics.cellH));
            return new Holder(t);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            h.tile.bind(gridApps.get(position));
            h.tile.setFocused(zone == GRID && position == gridIdx, false);
        }
    }
}
