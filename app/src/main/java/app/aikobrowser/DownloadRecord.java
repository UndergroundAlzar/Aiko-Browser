package app.aikobrowser;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** The private, durable record for the one current download. No authentication headers are saved. */
final class DownloadRecord {
    private static final int FORMAT = 1;
    static final int TRANSFERRING = 0, PUBLISHING = 1, COMPLETE = 2;
    final URL url;
    final String name, userAgent, output;
    final int lanes, phase;
    final long size;

    DownloadRecord(URL url, String name, String userAgent, int lanes) {
        this(url, name, userAgent, lanes, TRANSFERRING, "", -1);
    }

    private DownloadRecord(URL url, String name, String userAgent, int lanes, int phase, String output, long size) {
        this.url = url;
        this.name = name;
        this.userAgent = userAgent == null ? "" : userAgent;
        this.lanes = lanes;
        this.phase = phase;
        this.output = output;
        this.size = size;
    }

    DownloadRecord publication(int phase, String output, long size) {
        return new DownloadRecord(url, name, userAgent, lanes, phase, output, size);
    }

    void write(File folder) throws IOException {
        File draft = new File(folder, "current.tmp");
        try (FileOutputStream file = new FileOutputStream(draft);
             DataOutputStream data = new DataOutputStream(new BufferedOutputStream(file))) {
            data.writeInt(FORMAT);
            data.writeUTF(url.toExternalForm());
            data.writeUTF(name);
            data.writeUTF(userAgent);
            data.writeInt(lanes);
            data.writeInt(phase);
            data.writeUTF(output);
            data.writeLong(size);
            data.flush();
            file.getFD().sync();
        }
        Files.move(draft.toPath(), target(folder).toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static DownloadRecord read(File folder) {
        File target = target(folder);
        if (!target.isFile()) return null;
        try (DataInputStream data = new DataInputStream(new BufferedInputStream(new FileInputStream(target)))) {
            if (data.readInt() != FORMAT) return null;
            URL url = new URL(data.readUTF());
            RangeDownload.requireHttp(url);
            String name = data.readUTF(), userAgent = data.readUTF();
            int lanes = data.readInt(), phase = data.readInt();
            String output = data.readUTF();
            long size = data.readLong();
            if (lanes < 1 || lanes > RangeDownload.MAX_LANES || phase < TRANSFERRING || phase > COMPLETE
                    || (phase != TRANSFERRING && (size < 0 || !output.startsWith("content://media/")))) return null;
            return new DownloadRecord(url, name, userAgent, lanes, phase, output, size);
        } catch (IOException ignored) {
            return null;
        }
    }

    static void clear(File folder) throws IOException {
        Files.deleteIfExists(target(folder).toPath());
        Files.deleteIfExists(new File(folder, "current.tmp").toPath());
    }

    private static File target(File folder) { return new File(folder, "current.job"); }
}
