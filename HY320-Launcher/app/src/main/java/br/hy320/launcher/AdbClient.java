package br.hy320.launcher;

import android.content.Context;
import android.util.Base64;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Cliente ADB mínimo que fala com o adbd do próprio projetor (127.0.0.1:5555).
 * Serve pra mandar teclas de verdade (input keyevent), que funcionam em qualquer
 * app — no Android 11 a acessibilidade não tem setas e o YouTube ignora o foco.
 * Na primeira vez o projetor pergunta "Permitir depuração?" — marcar "Sempre permitir".
 */
final class AdbClient {

    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f;
    private static final int OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000001, MAX_DATA = 256 * 1024;
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private static AdbClient shared;
    /** Último erro, mostrado no /status. */
    static volatile String lastProblem;
    static volatile boolean ready;

    private final File keyDir;
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private int nextId = 1;

    private AdbClient(Context context) {
        keyDir = context.getApplicationContext().getFilesDir();
    }

    static synchronized AdbClient get(Context context) {
        if (shared == null) shared = new AdbClient(context);
        return shared;
    }

    /** Conecta em segundo plano, pra pergunta de autorização aparecer logo no projetor. */
    static void warmUp(Context context) {
        AdbClient client = get(context);
        new Thread(() -> client.exec("true"), "adb-warmup").start();
    }

    static boolean key(Context context, int keyCode) {
        return get(context).exec("input keyevent " + keyCode);
    }

    synchronized boolean exec(String command) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (socket == null) connect();
                run(command);
                ready = true;
                lastProblem = null;
                return true;
            } catch (IOException | GeneralSecurityException e) {
                lastProblem = e.getMessage();
                close();
            }
        }
        ready = false;
        return false;
    }

    private void connect() throws IOException, GeneralSecurityException {
        socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", 5555), 1500);
        socket.setSoTimeout(5000);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();

        send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
        KeyPair keys = keys();
        boolean sentSignature = false, sentPublicKey = false;
        while (true) {
            Message m = read();
            if (m.command == CNXN) break;
            if (m.command != AUTH || m.arg0 != 1) continue;
            if (!sentSignature) {
                send(AUTH, 2, 0, sign(keys, m.data));
                sentSignature = true;
            } else if (!sentPublicKey) {
                // Chave nova: o projetor mostra a pergunta; espera o usuário responder.
                send(AUTH, 3, 0, publicKey((RSAPublicKey) keys.getPublic()));
                sentPublicKey = true;
                socket.setSoTimeout(60_000);
            } else {
                throw new IOException("depuração não autorizada no projetor");
            }
        }
        socket.setSoTimeout(5000);
    }

    private void run(String command) throws IOException {
        int local = nextId++;
        send(OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        while (true) {
            Message m = read();
            if (m.arg1 != local) continue;
            if (m.command == WRTE) {
                send(OKAY, local, m.arg0, null);
            } else if (m.command == CLSE) {
                send(CLSE, local, m.arg0, null);
                return;
            }
        }
    }

    private void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
    }

    // ---------- Protocolo ----------

    private static final class Message {
        int command, arg0, arg1;
        byte[] data;
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        int length = data == null ? 0 : data.length;
        int checksum = 0;
        for (int i = 0; i < length; i++) checksum += data[i] & 0xff;
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(length).putInt(checksum).putInt(~command);
        out.write(header.array());
        if (length > 0) out.write(data);
        out.flush();
    }

    private Message read() throws IOException {
        byte[] raw = new byte[24];
        in.readFully(raw);
        ByteBuffer header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = header.getInt();
        m.arg0 = header.getInt();
        m.arg1 = header.getInt();
        int length = header.getInt();
        if (length < 0 || length > MAX_DATA) throw new IOException("resposta inválida do adb");
        m.data = new byte[length];
        in.readFully(m.data);
        return m;
    }

    // ---------- Chaves ----------

    private KeyPair keys() throws IOException, GeneralSecurityException {
        File privateFile = new File(keyDir, "adbkey");
        File publicFile = new File(keyDir, "adbkey.pub");
        KeyFactory factory = KeyFactory.getInstance("RSA");
        if (privateFile.exists() && publicFile.exists()) {
            return new KeyPair(
                    factory.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(publicFile.toPath()))),
                    factory.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privateFile.toPath()))));
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        Files.write(privateFile.toPath(), pair.getPrivate().getEncoded());
        Files.write(publicFile.toPath(), pair.getPublic().getEncoded());
        return pair;
    }

    /** O adbd manda um token de 20 bytes e espera um RSA/SHA1 "pré-digerido". */
    private static byte[] sign(KeyPair keys, byte[] token) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keys.getPrivate());
        cipher.update(SHA1_PREFIX);
        return cipher.doFinal(token);
    }

    /** Chave pública no formato do Android (mincrypt), em base64 + nome. */
    private static byte[] publicKey(RSAPublicKey key) {
        BigInteger r32 = BigInteger.ZERO.setBit(32);
        BigInteger n = key.getModulus();
        BigInteger rr = BigInteger.ZERO.setBit(2048).modPow(BigInteger.valueOf(2), n);
        BigInteger n0inv = n.remainder(r32).modInverse(r32);

        ByteBuffer buffer = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(64);
        buffer.putInt(n0inv.negate().intValue());
        BigInteger value = n;
        for (int i = 0; i < 64; i++) {
            BigInteger[] parts = value.divideAndRemainder(r32);
            value = parts[0];
            buffer.putInt(parts[1].intValue());
        }
        value = rr;
        for (int i = 0; i < 64; i++) {
            BigInteger[] parts = value.divideAndRemainder(r32);
            value = parts[0];
            buffer.putInt(parts[1].intValue());
        }
        buffer.putInt(key.getPublicExponent().intValue());
        String text = Base64.encodeToString(buffer.array(), Base64.NO_WRAP) + " HY-Home@hy320\0";
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
