package app.aikobrowser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Atomic FIFO and publication journal. Only the head owns a transfer or a MediaStore row. */
final class DownloadQueue {
    private static final int FORMAT = 1, MAX_PENDING = 4096, MAX_HISTORY = 30;
    final File folder;
    private List<Entry> pending = Collections.emptyList(), history = Collections.emptyList();
    private boolean running;

    static final class Entry {
        final String id;
        final DownloadRecord record;
        Entry(String id, DownloadRecord record) { this.id = id; this.record = record; }
        File part(File folder) { return new File(folder, id + ".part"); }
    }

    DownloadQueue(File folder) throws IOException {
        this.folder = folder;
        File manifest = new File(folder, "queue.bin");
        if (manifest.exists()) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(manifest)))) {
                if (in.readInt() != FORMAT) throw new IOException("无法识别下载队列格式");
                running = in.readBoolean();
                Set<String> seen = new HashSet<>();
                pending = readEntries(in, false, seen);
                history = readEntries(in, true, seen);
                if (in.read() != -1) throw new IOException("下载队列数据无效");
            }
        } else {
            DownloadRecord old = DownloadRecord.read(folder);
            if (old == null) return;
            // Reuse the legacy URL-keyed .part and .part.state in place, even during migration.
            Entry entry = new Entry(legacyId(old), old);
            List<Entry> one = Collections.singletonList(entry);
            if (old.phase == DownloadRecord.COMPLETE) commit(pending, one, false);
            else commit(one, history, false);
        }
        // A crash after the rename can leave current.job behind. queue.bin always wins.
        try { DownloadRecord.clear(folder); } catch (IOException ignored) { }
    }

    private static String legacyId(DownloadRecord record) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    record.url.toExternalForm().getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder(64);
            for (byte b : hash) key.append(Character.forDigit((b & 255) >>> 4, 16))
                    .append(Character.forDigit(b & 15, 16));
            return key.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static List<Entry> readEntries(DataInput in, boolean completed, Set<String> seen) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > (completed ? MAX_HISTORY : MAX_PENDING)) throw new IOException("下载队列长度无效");
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = in.readUTF();
            DownloadRecord record = DownloadRecord.readFrom(in);
            if (!id.matches("[a-f0-9]{32}|[a-f0-9]{64}") || !seen.add(id)
                    || completed != (record.phase == DownloadRecord.COMPLETE)
                    || (!completed && i > 0 && record.phase != DownloadRecord.TRANSFERRING)) {
                throw new IOException("下载队列条目无效");
            }
            entries.add(new Entry(id, record));
        }
        return Collections.unmodifiableList(entries);
    }

    private static void writeEntries(DataOutput out, List<Entry> entries) throws IOException {
        out.writeInt(entries.size());
        for (Entry entry : entries) {
            out.writeUTF(entry.id);
            entry.record.writeTo(out);
        }
    }

    // All mutations run on the service's metadata executor. Publish snapshots only after rename succeeds.
    private void commit(List<Entry> next, List<Entry> done, boolean run) throws IOException {
        Files.createDirectories(folder.toPath());
        File draft = new File(folder, "queue.tmp");
        try (FileOutputStream file = new FileOutputStream(draft);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(file))) {
            out.writeInt(FORMAT);
            out.writeBoolean(run);
            writeEntries(out, next);
            writeEntries(out, done);
            out.flush();
            file.getFD().sync();
        }
        Files.move(draft.toPath(), new File(folder, "queue.bin").toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        pending = Collections.unmodifiableList(next);
        history = Collections.unmodifiableList(done);
        running = run;
    }

    Entry head() { return pending.isEmpty() ? null : pending.get(0); }
    List<Entry> pending() { return pending; }
    List<Entry> history() { return history; }
    boolean running() { return running; }

    void enqueue(DownloadRecord record) throws IOException {
        if (pending.size() >= MAX_PENDING) throw new IOException("下载队列已满");
        List<Entry> next = new ArrayList<>(pending);
        next.add(new Entry(UUID.randomUUID().toString().replace("-", ""), record));
        commit(next, history, running || pending.isEmpty());
    }

    void setRunning(boolean value) throws IOException {
        if (value != running) commit(pending, history, value);
    }

    boolean dropWaiting(String id) throws IOException {
        List<Entry> next = new ArrayList<>(pending);
        for (int i = 1; i < next.size(); i++) {
            if (next.get(i).id.equals(id)) {
                next.remove(i);
                commit(next, history, running);
                return true;
            }
        }
        return false;
    }

    private List<Entry> copyAfterHead(String id) throws IOException {
        if (head() == null || !head().id.equals(id)) throw new IOException("下载任务已切换");
        return new ArrayList<>(pending.subList(1, pending.size()));
    }

    void publishing(String id, String uri, long size) throws IOException {
        List<Entry> next = copyAfterHead(id);
        next.add(0, new Entry(id, head().record.publication(DownloadRecord.PUBLISHING, uri, size)));
        commit(next, history, running);
    }

    void complete(String id) throws IOException {
        List<Entry> next = copyAfterHead(id);
        DownloadRecord record = head().record;
        if (record.phase != DownloadRecord.PUBLISHING) throw new IOException("缺少发布记录");
        List<Entry> done = new ArrayList<>();
        done.add(new Entry(id, record.publication(DownloadRecord.COMPLETE, record.output, record.size)));
        done.addAll(history.subList(0, Math.min(history.size(), MAX_HISTORY - 1)));
        commit(next, done, running && !next.isEmpty());
    }

    void cancelHead(String id) throws IOException {
        List<Entry> next = copyAfterHead(id);
        commit(next, history, !next.isEmpty());
    }
}
