package app.aikobrowser;

import java.io.*;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Password-protected .aiko envelope. Independent 64 KiB GCM records keep providers
 * from buffering a whole archive. The authenticated empty final record is mandatory. */
final class BackupEnvelope {
    private static final byte[] MAGIC = {'A','I','K','O','E','N','C',1};
    private static final int SALT = 16, PREFIX = 8, CHUNK = 65536, TAG = 16;
    private static final long MAX_ZIP_BYTES = BackupManager.MAX_TOTAL_BYTES + 1024 * 1024;
    private static final int ITERATIONS = 600_000;

    private BackupEnvelope() {}

    static boolean encrypted(BufferedInputStream in) throws IOException {
        in.mark(MAGIC.length);
        try {
            for (byte expected : MAGIC) if (in.read() != (expected & 255)) return false;
            return true;
        } finally { in.reset(); }
    }

    // Nonce = random prefix + record ordinal (big endian). A fresh salt creates a new key.
    private static Cipher cipher(int mode, SecretKeySpec key, byte[] header, int ordinal)
            throws GeneralSecurityException {
        byte[] nonce = new byte[12];
        System.arraycopy(header, MAGIC.length + SALT, nonce, 0, PREFIX);
        for (int i = 0; i < 4; i++) nonce[11 - i] = (byte)(ordinal >>> (8 * i));
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, key, new GCMParameterSpec(TAG * 8, nonce));
        c.updateAAD(header);
        return c;
    }

    private static SecretKeySpec derive(char[] password, byte[] header) throws GeneralSecurityException {
        byte[] salt = Arrays.copyOfRange(header, MAGIC.length, MAGIC.length + SALT);
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        try {
            byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            try { return new SecretKeySpec(raw, "AES"); } finally { Arrays.fill(raw, (byte)0); }
        } finally { spec.clearPassword(); }
    }

    static void encrypt(File zip, OutputStream out, char[] password) throws IOException {
        if (password == null || password.length < 8) throw new IOException("备份密码至少需要 8 个字符");
        if (zip.length() > MAX_ZIP_BYTES) throw new IOException("备份过大");
        byte[] header = new byte[MAGIC.length + SALT + PREFIX];
        new SecureRandom().nextBytes(header);
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.length);
        DataOutputStream data = new DataOutputStream(out);
        byte[] buf = new byte[CHUNK];
        try (InputStream in = new FileInputStream(zip)) {
            SecretKeySpec key = derive(password, header);
            data.write(header);
            for (int ordinal = 0;; ordinal++) {
                int n = in.read(buf);
                int size = n < 0 ? 0 : n;
                data.writeInt(size);
                data.write(cipher(Cipher.ENCRYPT_MODE, key, header, ordinal).doFinal(buf, 0, size));
                if (n < 0) break;
            }
            data.flush();
        } catch (GeneralSecurityException e) { throw new IOException("备份加密失败"); }
        finally { Arrays.fill(buf, (byte)0); }
    }

    static void unpack(BufferedInputStream in, OutputStream out, char[] password) throws IOException {
        if (!encrypted(in)) {
            if (password != null) throw new IOException("所选文件不是加密备份");
            BackupManager.copyBounded(in, out, MAX_ZIP_BYTES);
            return;
        }
        if (password == null) throw new IOException("需要备份密码");
        DataInputStream data = new DataInputStream(in);
        byte[] header = new byte[MAGIC.length + SALT + PREFIX];
        data.readFully(header);
        byte[] buf = new byte[CHUNK + TAG];
        long total = 0;
        try {
            SecretKeySpec key = derive(password, header);
            for (int i = 0;; i++) {
                int size = data.readInt();
                if (size < 0 || size > CHUNK || (total += size) > MAX_ZIP_BYTES || i > MAX_ZIP_BYTES / CHUNK + 2) {
                    throw new IOException("加密备份格式无效");
                }
                data.readFully(buf, 0, size + TAG);
                byte[] plain = cipher(Cipher.DECRYPT_MODE, key, header, i).doFinal(buf, 0, size + TAG);
                try { out.write(plain); } finally { Arrays.fill(plain, (byte)0); }
                if (size == 0) {
                    if (in.read() != -1) throw new IOException("加密备份含有多余数据");
                    return;
                }
            }
        } catch (GeneralSecurityException e) { throw new IOException("密码不正确或备份已损坏"); }
    }
}
