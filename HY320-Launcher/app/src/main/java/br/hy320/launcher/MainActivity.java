package br.hy320.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int COLUMNS = 6;
    private static final String PREFS = "launcher";
    private static final String KEY_FAVORITES = "favorites";

    // Usados só na primeira abertura, se estiverem instalados.
    private static final String[] DEFAULT_FAVORITES = {
            "com.google.android.youtube.tv",
            "com.google.android.youtube",
            "com.netflix.ninja",
            "com.netflix.mediaclient",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus",
            "com.globo.globotv",
            "com.spotify.tv.android",
            "com.spotify.music",
    };

    private static final View.OnFocusChangeListener FOCUS_ZOOM = (v, hasFocus) -> {
        float scale = hasFocus ? 1.08f : 1f;
        v.animate().scaleX(scale).scaleY(scale).translationZ(hasFocus ? 12f : 0f)
                .setDuration(150).start();
    };

    static final class AppInfo {
        final String pkg;
        final String label;
        final Drawable icon;
        final Intent launch;

        AppInfo(String pkg, String label, Drawable icon, Intent launch) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
            this.launch = launch;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, AppInfo> apps = new LinkedHashMap<>();
    private final List<String> favorites = new ArrayList<>();
    private volatile String appsSignature = "";

    private ScrollView scroll;
    private LinearLayout favoritesRow;
    private LinearLayout shortcutsRow;
    private LinearLayout appsGrid;
    private TextView greeting;
    private TextView networkStatus;
    private LayoutInflater inflater;
    private SharedPreferences prefs;

    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        inflater = getLayoutInflater();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        scroll = findViewById(R.id.scroll);
        favoritesRow = findViewById(R.id.favorites_row);
        shortcutsRow = findViewById(R.id.shortcuts_row);
        appsGrid = findViewById(R.id.apps_grid);
        greeting = findViewById(R.id.greeting);
        networkStatus = findViewById(R.id.network_status);

        String saved = prefs.getString(KEY_FAVORITES, "");
        for (String pkg : saved.split(",")) {
            if (!pkg.isEmpty()) favorites.add(pkg);
        }

        addShortcut(R.drawable.ic_wifi, "Wi-Fi", Settings.ACTION_WIFI_SETTINGS);
        addShortcut(R.drawable.ic_bluetooth, "Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS);
        addShortcut(R.drawable.ic_volume, "Som", Settings.ACTION_SOUND_SETTINGS);
        addShortcut(R.drawable.ic_settings, "Configurações", Settings.ACTION_SETTINGS);
        shortcutsRow.getChildAt(0).requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(statusTicker);
        loadApps();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(statusTicker);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Apertou HOME já estando no launcher: volta pro topo.
        scroll.smoothScrollTo(0, 0);
        focusFirst();
    }

    @Override
    public void onBackPressed() {
        // Tela inicial: voltar não faz nada.
    }

    // ---------- Apps ----------

    private void loadApps() {
        executor.execute(() -> {
            PackageManager pm = getPackageManager();
            Map<String, ResolveInfo> found = new HashMap<>();
            Map<String, String> categories = new HashMap<>();
            collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, found, categories);
            collect(pm, Intent.CATEGORY_LAUNCHER, found, categories);

            List<String> keys = new ArrayList<>(found.keySet());
            Collections.sort(keys);
            String signature = TextUtils.join(",", keys);
            // Nada instalado/removido desde a última vez: não recarrega ícones.
            if (signature.equals(appsSignature)) return;

            List<AppInfo> list = new ArrayList<>();
            for (String pkg : keys) {
                ResolveInfo ri = found.get(pkg);
                Intent launch = new Intent(Intent.ACTION_MAIN)
                        .addCategory(categories.get(pkg))
                        .setComponent(new ComponentName(pkg, ri.activityInfo.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                list.add(new AppInfo(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm), launch));
            }
            Collator collator = Collator.getInstance();
            list.sort((a, b) -> collator.compare(a.label, b.label));

            handler.post(() -> {
                appsSignature = signature;
                apps.clear();
                for (AppInfo app : list) apps.put(app.pkg, app);
                render();
            });
        });
    }

    private void collect(PackageManager pm, String category,
                         Map<String, ResolveInfo> found, Map<String, String> categories) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || found.containsKey(pkg)) continue;
            found.put(pkg, ri);
            categories.put(pkg, category);
        }
    }

    private void render() {
        if (!prefs.contains(KEY_FAVORITES)) seedFavorites();
        renderFavorites();
        renderGrid();
        View focused = getCurrentFocus();
        if (focused == null || !focused.isAttachedToWindow()) focusFirst();
    }

    private void seedFavorites() {
        for (String pkg : DEFAULT_FAVORITES) {
            if (apps.containsKey(pkg)) favorites.add(pkg);
        }
        if (favorites.isEmpty()) {
            for (String pkg : apps.keySet()) {
                if (favorites.size() >= 5) break;
                favorites.add(pkg);
            }
        }
        saveFavorites();
    }

    private void renderFavorites() {
        favoritesRow.removeAllViews();
        for (String pkg : favorites) {
            AppInfo app = apps.get(pkg);
            if (app == null) continue;
            View card = inflater.inflate(R.layout.item_favorite, favoritesRow, false);
            bindApp(card, app);
            favoritesRow.addView(card);
        }
    }

    private void renderGrid() {
        appsGrid.removeAllViews();
        LinearLayout row = null;
        int i = 0;
        for (AppInfo app : apps.values()) {
            if (i++ % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                appsGrid.addView(row);
            }
            View tile = inflater.inflate(R.layout.item_tile, row, false);
            bindApp(tile, app);
            row.addView(tile);
        }
        // Completa a última linha com espaços vazios pra manter o tamanho das colunas.
        while (row != null && row.getChildCount() < COLUMNS) {
            View spacer = inflater.inflate(R.layout.item_tile, row, false);
            spacer.setVisibility(View.INVISIBLE);
            row.addView(spacer);
        }
    }

    private void bindApp(View view, AppInfo app) {
        Drawable.ConstantState state = app.icon.getConstantState();
        Drawable icon = state != null ? state.newDrawable(getResources()) : app.icon;
        ((ImageView) view.findViewById(R.id.icon)).setImageDrawable(icon);
        ((TextView) view.findViewById(R.id.label)).setText(app.label);
        view.setTag(app.pkg);
        view.setOnFocusChangeListener(FOCUS_ZOOM);
        view.setOnClickListener(v -> startSafely(app.launch));
        view.setOnLongClickListener(v -> {
            showAppMenu(app);
            return true;
        });
    }

    private void focusFirst() {
        View target = favoritesRow.getChildCount() > 0
                ? favoritesRow.getChildAt(0)
                : shortcutsRow.getChildAt(0);
        target.requestFocus();
    }

    // ---------- Favoritos ----------

    private void showAppMenu(AppInfo app) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        int index = favorites.indexOf(app.pkg);

        if (index >= 0) {
            labels.add("Remover dos favoritos");
            actions.add(() -> {
                favorites.remove(app.pkg);
                onFavoritesChanged(null);
            });
            if (index > 0) {
                labels.add("Mover para a esquerda");
                actions.add(() -> moveFavorite(index, index - 1));
            }
            if (index < favorites.size() - 1) {
                labels.add("Mover para a direita");
                actions.add(() -> moveFavorite(index, index + 1));
            }
        } else {
            labels.add("Fixar nos favoritos");
            actions.add(() -> {
                favorites.add(app.pkg);
                onFavoritesChanged(app.pkg);
            });
        }
        labels.add("Informações do app");
        actions.add(() -> startSafely(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + app.pkg))));
        labels.add("Desinstalar");
        actions.add(() -> startSafely(new Intent(Intent.ACTION_DELETE,
                Uri.parse("package:" + app.pkg))));

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(app.label)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                .show();
    }

    private void moveFavorite(int from, int to) {
        String pkg = favorites.get(from);
        Collections.swap(favorites, from, to);
        onFavoritesChanged(pkg);
    }

    private void onFavoritesChanged(String focusPkg) {
        saveFavorites();
        renderFavorites();
        View target = focusPkg != null ? favoritesRow.findViewWithTag(focusPkg) : null;
        if (target != null) {
            target.requestFocus();
        } else {
            View focused = getCurrentFocus();
            if (focused == null || !focused.isAttachedToWindow()) focusFirst();
        }
    }

    private void saveFavorites() {
        prefs.edit().putString(KEY_FAVORITES, TextUtils.join(",", favorites)).apply();
    }

    // ---------- Atalhos e status ----------

    private void addShortcut(int iconRes, String label, String action) {
        View item = inflater.inflate(R.layout.item_shortcut, shortcutsRow, false);
        ((ImageView) item.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) item.findViewById(R.id.label)).setText(label);
        item.setOnFocusChangeListener(FOCUS_ZOOM);
        item.setOnClickListener(v -> {
            if (!startSafely(new Intent(action))) startSafely(new Intent(Settings.ACTION_SETTINGS));
        });
        shortcutsRow.addView(item);
    }

    private boolean startSafely(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Não foi possível abrir", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void updateStatus() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(hour < 5 ? "Boa noite" : hour < 12 ? "Bom dia" : hour < 18 ? "Boa tarde" : "Boa noite");

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        String text;
        if (caps == null) {
            text = "Sem conexão";
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            text = "Wi-Fi conectado";
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            text = "Cabo conectado";
        } else {
            text = "Conectado";
        }
        networkStatus.setText(text);
        networkStatus.setAlpha(caps == null ? 0.5f : 1f);
    }
}
