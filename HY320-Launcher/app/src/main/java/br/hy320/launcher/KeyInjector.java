package br.hy320.launcher;

import android.os.Process;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Aperta teclas escrevendo direto no teclado físico do projetor (/dev/input).
 * No firmware do HY320 esse arquivo é gravável por qualquer app, então é o jeito
 * mais rápido; se não der, o Commands cai pro ADB ou pra acessibilidade.
 */
final class KeyInjector {

    static final int KEY_POWER = 116;

    private static final String[] DEVICE_NAMES = {"sunxi-keyboard", "sunxi-ir-uinput"};
    private static File device;
    private static boolean searched;

    private KeyInjector() {
    }

    /** Código de tecla do Linux (não o do Android) pro comando do celular; 0 se não existir. */
    static int code(String key) {
        switch (key) {
            case "up": return 103;
            case "down": return 108;
            case "left": return 105;
            case "right": return 106;
            case "ok": return 352;
            case "back": return 158;
            case "home": return 102;
            default: return 0;
        }
    }

    static boolean available() {
        synchronized (KeyInjector.class) {
            return device() != null;
        }
    }

    static synchronized boolean press(int code) {
        File dev = device();
        if (dev == null) return false;
        try (FileOutputStream out = new FileOutputStream(dev)) {
            out.write(events(code, 1));
            out.write(events(code, 0));
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    private static File device() {
        if (searched) return device;
        searched = true;
        for (String wanted : DEVICE_NAMES) {
            for (int i = 0; i < 16; i++) {
                File name = new File("/sys/class/input/event" + i + "/device/name");
                try {
                    String found = new String(Files.readAllBytes(name.toPath()), StandardCharsets.UTF_8).trim();
                    File dev = new File("/dev/input/event" + i);
                    if (wanted.equals(found) && dev.canWrite()) return device = dev;
                } catch (IOException | SecurityException ignored) {
                }
            }
        }
        return null;
    }

    /** struct input_event: tecla + SYN. O tamanho do horário muda entre 32 e 64 bits. */
    private static byte[] events(int code, int value) {
        int timeSize = Process.is64Bit() ? 16 : 8;
        ByteBuffer buffer = ByteBuffer.allocate((timeSize + 8) * 2).order(ByteOrder.nativeOrder());
        buffer.put(new byte[timeSize]).putShort((short) 1).putShort((short) code).putInt(value);
        buffer.put(new byte[timeSize]).putShort((short) 0).putShort((short) 0).putInt(0);
        return buffer.array();
    }
}
