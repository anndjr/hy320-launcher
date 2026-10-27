package br.hy320.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.net.Inet4Address;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int COLUMNS = 6;
    private static final String PREFS = "launcher";
    private static final String KEY_FAVORITES = "favorites";
    private static final String KEY_HIDDEN = "hidden";
    private static final String KEY_BOOT_APP = "boot_app";
    private static final String KEY_LAST_BOOT = "last_boot";

    /** Marcado pelo timer de dormir: ao voltar pro início, abre a tela escura. */
    static volatile boolean sleepRequested;

    // Usados só na primeira abertura, se estiverem instalados.
    private static final String[] DEFAULT_FAVORITES = {
            "com.teamsmart.videomanager.tv",
            "com.liskovsoft.smarttubetv.beta",
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
    private final Set<String> hidden = new HashSet<>();
    private volatile String appsSignature = "";
    private long wallpaperStamp = -1;
    private boolean bootChecked;

    private ScrollView scroll;
    private LinearLayout favoritesRow;
    private LinearLayout shortcutsRow;
    private LinearLayout appsGrid;
    private TextView greeting;
    private TextView networkStatus;
    private TextView memoryStatus;
    private TextView sleepStatus;
    private ImageView wallpaper;
    private View wallpaperDim;
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
        memoryStatus = findViewById(R.id.memory_status);
        sleepStatus = findViewById(R.id.sleep_status);
        wallpaper = findViewById(R.id.wallpaper);
        wallpaperDim = findViewById(R.id.wallpaper_dim);

        String saved = prefs.getString(KEY_FAVORITES, "");
        for (String pkg : saved.split(",")) {
            if (!pkg.isEmpty()) favorites.add(pkg);
        }
        for (String pkg : prefs.getString(KEY_HIDDEN, "").split(",")) {
            if (!pkg.isEmpty()) hidden.add(pkg);
        }

        addShortcut(R.drawable.ic_phone, "Celular",
                () -> startSafely(new Intent(this, ReceiveActivity.class)));
        addShortcut(R.drawable.ic_timer, "Timer de dormir", this::showSleepTimer);
        addShortcut(R.drawable.ic_flash, "Limpar memória", this::cleanMemory);
        addShortcut(R.drawable.ic_wifi, "Wi-Fi", () -> openSettings(Settings.ACTION_WIFI_SETTINGS));
        addShortcut(R.drawable.ic_bluetooth, "Bluetooth", () -> openSettings(Settings.ACTION_BLUETOOTH_SETTINGS));
        addShortcut(R.drawable.ic_volume, "Som", () -> openSettings(Settings.ACTION_SOUND_SETTINGS));
        addShortcut(R.drawable.ic_info, "Sistema", this::showSystemInfo);
        addShortcut(R.drawable.ic_settings, "Configurações", () -> openSettings(Settings.ACTION_SETTINGS));
        shortcutsRow.getChildAt(0).requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sleepRequested) {
            sleepRequested = false;
            startActivity(new Intent(this, SleepActivity.class));
        }
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
        refocusIfLost();
        if (!bootChecked) {
            bootChecked = true;
            maybeLaunchOnBoot();
        }
    }

    private void refocusIfLost() {
        View focused = getCurrentFocus();
        if (focused == null || !focused.isAttachedToWindow()) focusFirst();
    }

    /** Abre o app escolhido uma vez por inicialização do projetor. */
    private void maybeLaunchOnBoot() {
        long bootId = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (bootId < 0) bootId = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60000;
        if (bootId == prefs.getLong(KEY_LAST_BOOT, Long.MIN_VALUE)) return;
        prefs.edit().putLong(KEY_LAST_BOOT, bootId).apply();

        AppInfo app = apps.get(prefs.getString(KEY_BOOT_APP, ""));
        if (app != null) handler.postDelayed(() -> startSafely(app.launch), 800);
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
        List<View> tiles = new ArrayList<>();
        int hiddenCount = 0;
        for (AppInfo app : apps.values()) {
            if (hidden.contains(app.pkg)) {
                hiddenCount++;
                continue;
            }
            View tile = inflater.inflate(R.layout.item_tile, appsGrid, false);
            bindApp(tile, app);
            tiles.add(tile);
        }
        if (hiddenCount > 0) {
            View tile = inflater.inflate(R.layout.item_tile, appsGrid, false);
            ((ImageView) tile.findViewById(R.id.icon)).setImageResource(R.drawable.ic_hidden);
            ((TextView) tile.findViewById(R.id.label)).setText("Ocultos (" + hiddenCount + ")");
            tile.setOnFocusChangeListener(FOCUS_ZOOM);
            tile.setOnClickListener(v -> showHiddenApps());
            tiles.add(tile);
        }

        LinearLayout row = null;
        for (int i = 0; i < tiles.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                appsGrid.addView(row);
            }
            row.addView(tiles.get(i));
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
        labels.add("Esconder app");
        actions.add(() -> {
            hidden.add(app.pkg);
            favorites.remove(app.pkg);
            saveHidden();
            onFavoritesChanged(null);
            renderGrid();
            refocusIfLost();
        });

        boolean opensOnBoot = app.pkg.equals(prefs.getString(KEY_BOOT_APP, ""));
        labels.add(opensOnBoot ? "Não abrir mais ao ligar" : "Abrir ao ligar o projetor");
        actions.add(() -> {
            prefs.edit().putString(KEY_BOOT_APP, opensOnBoot ? "" : app.pkg).apply();
            Toast.makeText(this, opensOnBoot ? "Ok, não abre mais sozinho"
                    : app.label + " vai abrir quando o projetor ligar", Toast.LENGTH_LONG).show();
        });

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

    // ---------- Apps ocultos ----------

    private void showHiddenApps() {
        List<AppInfo> list = new ArrayList<>();
        for (AppInfo app : apps.values()) {
            if (hidden.contains(app.pkg)) list.add(app);
        }
        String[] labels = new String[list.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = list.get(i).label;

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Escolha um app para mostrar de novo")
                .setItems(labels, (dialog, which) -> {
                    hidden.remove(list.get(which).pkg);
                    saveHidden();
                    renderGrid();
                    refocusIfLost();
                })
                .show();
    }

    private void saveHidden() {
        prefs.edit().putString(KEY_HIDDEN, TextUtils.join(",", hidden)).apply();
    }

    // ---------- Timer de dormir ----------

    private void showSleepTimer() {
        ControlService service = ControlService.instance;
        if (service == null) {
            showControlSetup();
            return;
        }
        int[] minutes = {15, 30, 60, 90, 0};
        String[] labels = {"15 minutos", "30 minutos", "1 hora", "1 hora e meia", "Cancelar timer"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Timer de dormir")
                .setItems(labels, (dialog, which) -> {
                    service.setSleepTimer(minutes[which]);
                    Toast.makeText(this, minutes[which] == 0 ? "Timer cancelado"
                            : "O projetor vai dormir em " + labels[which], Toast.LENGTH_LONG).show();
                    updateStatus();
                })
                .show();
    }

    private void showControlSetup() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Ative o HY Controle")
                .setMessage("O timer de dormir e o controle pelo celular precisam do HY Controle.\n\n"
                        + "Em Acessibilidade, abra \"HY Controle\" e ative. É só uma vez.")
                .setPositiveButton("Abrir Acessibilidade",
                        (dialog, which) -> openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                .setNegativeButton("Agora não", null)
                .show();
    }

    // ---------- Papel de parede ----------

    private void updateWallpaper() {
        File file = Commands.wallpaperFile(this);
        long stamp = file.exists() ? file.lastModified() : 0;
        if (stamp == wallpaperStamp) return;
        wallpaperStamp = stamp;

        if (stamp == 0) {
            showWallpaper(null);
            return;
        }
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        executor.execute(() -> {
            Bitmap bitmap = decodeSampled(file.getPath(), width, height);
            handler.post(() -> showWallpaper(bitmap));
        });
    }

    private void showWallpaper(Bitmap bitmap) {
        wallpaper.setImageBitmap(bitmap);
        int visibility = bitmap != null ? View.VISIBLE : View.GONE;
        wallpaper.setVisibility(visibility);
        wallpaperDim.setVisibility(visibility);
    }

    /** Carrega a imagem já reduzida pro tamanho da tela, pra não gastar RAM. */
    private static Bitmap decodeSampled(String path, int width, int height) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        int sample = 1;
        while (options.outWidth / (sample * 2) >= width && options.outHeight / (sample * 2) >= height) {
            sample *= 2;
        }
        options.inJustDecodeBounds = false;
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(path, options);
    }

    // ---------- Atalhos e status ----------

    private void addShortcut(int iconRes, String label, Runnable onClick) {
        View item = inflater.inflate(R.layout.item_shortcut, shortcutsRow, false);
        ((ImageView) item.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) item.findViewById(R.id.label)).setText(label);
        item.setOnFocusChangeListener(FOCUS_ZOOM);
        item.setOnClickListener(v -> onClick.run());
        shortcutsRow.addView(item);
    }

    private void openSettings(String action) {
        if (!startSafely(new Intent(action))) startSafely(new Intent(Settings.ACTION_SETTINGS));
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
        ControlService.watch(this);
        ControlService.ensureEnabled(this);
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

        memoryStatus.setText("RAM livre " + size(memoryInfo().availMem));

        ControlService service = ControlService.instance;
        long left = service != null ? service.getSleepAt() - System.currentTimeMillis() : 0;
        if (left > 0) {
            sleepStatus.setText("Dorme em " + ((left + 59_999) / 60_000) + " min");
            sleepStatus.setVisibility(View.VISIBLE);
        } else {
            sleepStatus.setVisibility(View.GONE);
        }

        updateWallpaper();
    }

    // ---------- Memória e sistema ----------

    private void cleanMemory() {
        Toast.makeText(this, "Limpando memória…", Toast.LENGTH_SHORT).show();
        List<String> packages = new ArrayList<>(apps.keySet());
        executor.execute(() -> {
            ActivityManager am = getSystemService(ActivityManager.class);
            long before = memoryInfo().availMem;
            for (String pkg : packages) am.killBackgroundProcesses(pkg);
            SystemClock.sleep(800); // dá tempo do sistema recolher a memória
            long freed = memoryInfo().availMem - before;
            handler.post(() -> {
                String msg = freed > 1024 * 1024
                        ? "Memória liberada: " + size(freed)
                        : "A memória já estava limpa";
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                updateStatus();
            });
        });
    }

    private void showSystemInfo() {
        ActivityManager.MemoryInfo mem = memoryInfo();
        StatFs disk = new StatFs(Environment.getDataDirectory().getPath());
        String ip = ipAddress(this);
        String info = "Modelo: " + Build.MANUFACTURER + " " + Build.MODEL
                + "\nAndroid: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + "\n\nRAM livre: " + size(mem.availMem) + " de " + size(mem.totalMem)
                + "\nArmazenamento livre: " + size(disk.getAvailableBytes()) + " de " + size(disk.getTotalBytes())
                + "\n\nIP: " + (ip != null ? ip : "sem conexão")
                + "\nLigado há: " + uptime()
                + "\n\nHY Home " + versionName();

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Sistema")
                .setMessage(info)
                .setPositiveButton("Limpar memória", (dialog, which) -> cleanMemory())
                .setNegativeButton("Fechar", null)
                .show();
    }

    private ActivityManager.MemoryInfo memoryInfo() {
        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        getSystemService(ActivityManager.class).getMemoryInfo(mem);
        return mem;
    }

    private String size(long bytes) {
        return Formatter.formatShortFileSize(this, bytes);
    }

    /** IPv4 do projetor na rede local, ou null sem conexão. */
    static String ipAddress(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        LinkProperties link = cm == null ? null : cm.getLinkProperties(cm.getActiveNetwork());
        if (link != null) {
            for (LinkAddress address : link.getLinkAddresses()) {
                if (address.getAddress() instanceof Inet4Address) {
                    return address.getAddress().getHostAddress();
                }
            }
        }
        return null;
    }

    private String uptime() {
        long minutes = SystemClock.elapsedRealtime() / 60000;
        return minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min";
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }
}
