package br.hy320.launcher;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.ApplicationExitInfo;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Apps abertos": os apps usados desde que o projetor ligou e que ainda não foram fechados.
 * O Android esconde os processos dos outros apps, então a lista vem do histórico de uso
 * (permissão dada uma vez: adb shell appops set br.hy320.launcher GET_USAGE_STATS allow).
 */
final class OpenApps {

    private static final String PREFS = "open_apps";

    static final class App {
        final String pkg;
        final String label;

        App(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    private OpenApps() {
    }

    static boolean hasAccess(Context context) {
        AppOpsManager ops = context.getSystemService(AppOpsManager.class);
        return ops != null && ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    /** Do mais recente pro mais antigo; não inclui o próprio launcher. */
    static List<App> list(Context context) {
        List<App> apps = new ArrayList<>();
        if (!hasAccess(context)) return apps;
        UsageStatsManager usage = context.getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        long boot = now - SystemClock.elapsedRealtime();
        Map<String, Long> lastOpened = new HashMap<>();
        UsageEvents events = usage.queryEvents(boot, now);
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastOpened.put(event.getPackageName(), event.getTimeStamp());
            }
        }

        SharedPreferences closed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        PackageManager pm = context.getPackageManager();
        ActivityManager am = context.checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED
                ? context.getSystemService(ActivityManager.class) : null;
        List<Map.Entry<String, Long>> entries = new ArrayList<>(lastOpened.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Long> entry : entries) {
            String pkg = entry.getKey();
            if (pkg.equals(context.getPackageName())) continue;
            // Fechado por aqui depois da última vez que abriu.
            if (closed.getLong(pkg, 0) >= entry.getValue()) continue;
            // Ou fechado pelo próprio Android (falta de memória, travou...).
            if (diedAfter(am, pkg, entry.getValue())) continue;
            // Só apps que aparecem pra abrir (deixa de fora telas do sistema).
            if (pm.getLaunchIntentForPackage(pkg) == null && pm.getLeanbackLaunchIntentForPackage(pkg) == null) continue;
            try {
                apps.add(new App(pkg, pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()));
            } catch (PackageManager.NameNotFoundException ignored) {
                // Desinstalado depois de usado.
            }
        }
        return apps;
    }

    /**
     * O processo principal do app terminou depois da última vez que ele foi aberto?
     * Lê o histórico de saídas do Android 11 (precisa do DUMP: adb shell pm grant ... android.permission.DUMP).
     */
    private static boolean diedAfter(ActivityManager am, String pkg, long openedAt) {
        if (am == null || Build.VERSION.SDK_INT < 30) return false;
        try {
            for (ApplicationExitInfo info : am.getHistoricalProcessExitReasons(pkg, 0, 5)) {
                // A lista vem da saída mais recente pra mais antiga. Neste firmware nomes longos
                // vêm cortados ("com.softwinner.Tvd"), então vale o começo do nome também.
                String name = info.getProcessName();
                boolean main = pkg.equals(name) || (name != null && name.length() >= 15 && pkg.startsWith(name));
                if (main) return info.getTimestamp() > openedAt;
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
        return false;
    }

    /** Fecha o app; se ele está na tela, volta pro início antes (o Android não fecha o app da frente). */
    static void close(Context context, String pkg) {
        ControlService service = ControlService.instance;
        if (service != null && pkg.equals(service.foregroundPackage())) {
            service.key("home");
            SystemClock.sleep(800);
        }
        context.getSystemService(ActivityManager.class).killBackgroundProcesses(pkg);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(pkg, System.currentTimeMillis()).apply();
    }

    static int closeAll(Context context) {
        List<App> apps = list(context);
        for (App app : apps) close(context, app.pkg);
        return apps.size();
    }

    static String toJson(List<App> apps) {
        JSONArray array = new JSONArray();
        for (App app : apps) {
            JSONObject item = new JSONObject();
            try {
                item.put("pkg", app.pkg).put("label", app.label);
            } catch (org.json.JSONException ignored) {
            }
            array.put(item);
        }
        return array.toString();
    }

    static Intent launchIntent(Context context, String pkg) {
        PackageManager pm = context.getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }
}
