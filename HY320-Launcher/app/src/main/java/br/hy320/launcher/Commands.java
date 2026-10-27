package br.hy320.launcher;

import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Comandos que chegam do celular. Cada um retorna null se deu certo, ou a mensagem de erro. */
final class Commands {

    static final String NEED_CONTROL =
            "Ative o HY Controle no projetor (Acessibilidade). Veja a tela Celular no launcher.";

    private static final long MAX_WALLPAPER = 20L * 1024 * 1024;

    private Commands() {
    }

    static String key(Context context, String key) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        switch (key) {
            case "volup":
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                return null;
            case "voldown":
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                return null;
            case "mute":
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                return null;
            default:
                ControlService service = ControlService.instance;
                if (service == null) return NEED_CONTROL;
                return service.key(key) ? null : "Não deu pra fazer isso nessa tela";
        }
    }

    static String text(String text, boolean enter) {
        ControlService service = ControlService.instance;
        return service == null ? NEED_CONTROL : service.setText(text, enter);
    }

    static String openLink(Context context, String url) {
        url = url.trim();
        if (url.isEmpty()) return "Cole um link primeiro";
        if (!url.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) url = "https://" + url;
        return start(context, new Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                ? null : "Nenhum app do projetor abre esse link";
    }

    static String searchYouTube(Context context, String query) {
        query = query.trim();
        if (query.isEmpty()) return "Digite o que quer procurar";
        for (String pkg : new String[]{"com.google.android.youtube.tv", "com.google.android.youtube"}) {
            Intent search = new Intent(Intent.ACTION_SEARCH)
                    .setPackage(pkg)
                    .putExtra(SearchManager.QUERY, query);
            if (start(context, search)) return null;
        }
        Uri web = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query));
        return start(context, new Intent(Intent.ACTION_VIEW, web)) ? null : "YouTube não encontrado";
    }

    static String sleep(int minutes) {
        ControlService service = ControlService.instance;
        if (service == null) return NEED_CONTROL;
        service.setSleepTimer(minutes);
        return null;
    }

    static File wallpaperFile(Context context) {
        return new File(context.getFilesDir(), "wallpaper");
    }

    static String saveWallpaper(Context context, InputStream in, long length) throws IOException {
        if (length <= 0 || length > MAX_WALLPAPER) return "Imagem vazia ou maior que 20 MB";
        File tmp = new File(context.getFilesDir(), "wallpaper.tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[64 * 1024];
            long received = 0;
            while (received < length) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, length - received));
                if (n < 0) throw new IOException("Conexão interrompida");
                out.write(buffer, 0, n);
                received += n;
            }
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(tmp.getPath(), bounds);
        if (bounds.outWidth <= 0) {
            tmp.delete();
            return "Esse arquivo não é uma imagem";
        }
        if (!tmp.renameTo(wallpaperFile(context))) return "Não foi possível salvar a imagem";
        return null;
    }

    static String resetWallpaper(Context context) {
        File file = wallpaperFile(context);
        return !file.exists() || file.delete() ? null : "Não foi possível remover";
    }

    private static boolean start(Context context, Intent intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}
