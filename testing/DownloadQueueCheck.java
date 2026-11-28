package app.aikobrowser;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.*;

/** Host regression for both legacy inputs and every durable queue transition. */
public final class DownloadQueueCheck {
    private static final String ADDRESS = "https://example.org/archive.bin";
    private static final String ROW = "content://media/external/downloads/42";

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("aiko-queue-");
        try {
            migration(dir);
            transitions(dir.toFile());
            legacyPublishing(Files.createDirectory(dir.resolve("publishing")));
            legacyComplete(Files.createDirectory(dir.resolve("complete")));
            System.out.println("PASS legacy partial, publishing and completed imports; durable order, independent repeated URLs, pause, drop, retry, crash recovery and completion");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }

    private static DownloadRecord task(String name, int lanes) throws Exception {
        return new DownloadRecord(new URL(ADDRESS), name, "UA", lanes);
    }

    private static void migration(Path dir) throws Exception {
        // Hand-encode the v0.5.0 layout independently of the new reader/writer.
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(dir.resolve("current.job")))) {
            out.writeInt(1);
            out.writeUTF(ADDRESS);
            out.writeUTF("旧文件.bin");
            out.writeUTF("legacy agent");
            out.writeInt(128);
            out.writeInt(0);
            out.writeUTF("");
            out.writeLong(-1);
        }
        String partName = "fcddd774f4aa431e0329fddc82efeb47e4143ef396dfa89153088c6cf2c10bbd.part";
        Files.write(dir.resolve(partName), new byte[]{1, 2, 3});
        Files.write(dir.resolve(partName + ".state"), new byte[]{4});
        DownloadQueue queue = new DownloadQueue(dir.toFile());
        check(!queue.running() && queue.head().record.lanes == 128, "old head waits for explicit resume");
        check(queue.head().record.userAgent.equals("legacy agent"), "legacy user agent intact");
        check(queue.head().part(dir.toFile()).toPath().equals(dir.resolve(partName)), "old .part path preserved");
        check(Files.readAllBytes(dir.resolve(partName))[2] == 3 && Files.readAllBytes(dir.resolve(partName + ".state"))[0] == 4,
                "migration never rewrites old ranges");
    }

    private static void transitions(File dir) throws Exception {
        DownloadQueue queue = new DownloadQueue(dir);
        queue.enqueue(task("repeated.bin", 1));
        queue.enqueue(task("drop.bin", 8));
        DownloadQueue.Entry old = queue.head(), repeat = queue.pending().get(1), drop = queue.pending().get(2);
        check(!queue.running() && !old.part(dir).equals(repeat.part(dir)), "enqueue behind pause / distinct private files");
        check(!queue.dropWaiting(old.id) && queue.dropWaiting(drop.id), "drop only waiting rows");
        queue.setRunning(true);
        queue.enqueue(task("repeated.bin", 128));
        DownloadQueue.Entry again = queue.pending().get(2);
        check(!again.part(dir).equals(repeat.part(dir)), "new repeated submissions own separate staging files");
        queue = new DownloadQueue(dir);
        check(queue.running() && queue.pending().size() == 3 && queue.pending().get(1).id.equals(repeat.id)
                && queue.pending().get(2).id.equals(again.id), "reopen preserves FIFO");
        queue.setRunning(false);
        check(!new DownloadQueue(dir).running(), "user pause/error survives restart");
        queue.setRunning(true);
        queue.publishing(old.id, ROW, 3);
        queue = new DownloadQueue(dir);
        check(queue.head().record.output.equals(ROW) && queue.head().record.size == 3, "resume same pending MediaStore row");
        check(queue.head().record.phase == DownloadRecord.PUBLISHING, "interrupted publish remains head");
        // The provider has cleared IS_PENDING, then the process dies before the local commit.
        queue.complete(old.id);
        queue = new DownloadQueue(dir);
        check(queue.running() && queue.head().id.equals(repeat.id) && queue.history().size() == 1
                && queue.history().get(0).record.output.equals(ROW), "completion and next head committed together");
        try { queue.complete(old.id); throw new AssertionError("duplicate completion"); }
        catch (IOException expected) { }
        check(!queue.dropWaiting(repeat.id), "stale removal cannot drop the promoted head");
        // A failed journal write must change neither disk state nor the live snapshot.
        File draft = new File(dir, "queue.tmp");
        Files.createDirectory(draft.toPath());
        try { queue.cancelHead(repeat.id); throw new AssertionError("commit should fail"); }
        catch (IOException expected) { }
        check(queue.head().id.equals(repeat.id) && new DownloadQueue(dir).head().id.equals(repeat.id), "failed commit is retryable");
        Files.delete(draft.toPath());
        queue.cancelHead(repeat.id);
        check(queue.running() && queue.head().id.equals(again.id), "cancel promotes successor");
        queue.publishing(again.id, ROW + "1", 17);
        queue.complete(again.id);
        task("stale.bin", 9).write(dir);
        queue = new DownloadQueue(dir);
        check(queue.head() == null && !queue.running() && queue.history().size() == 2, "stale legacy record cannot replay");
        check(queue.history().get(0).record.size == 17 && queue.history().get(1).record.size == 3, "completed sizes/URIs retained");
        queue.enqueue(task("next", 3));
        check(queue.running() && queue.pending().size() == 1, "submission restarts idle queue");
    }

    private static void legacyPublishing(Path dir) throws Exception {
        task("interrupted.bin", 2).publication(DownloadRecord.PUBLISHING, ROW, 3).write(dir.toFile());
        DownloadQueue queue = new DownloadQueue(dir.toFile());
        check(queue.head().record.phase == DownloadRecord.PUBLISHING && queue.head().record.output.equals(ROW), "legacy pending row");
    }

    private static void legacyComplete(Path dir) throws Exception {
        task("done.bin", 4).publication(DownloadRecord.COMPLETE, ROW, 7).write(dir.toFile());
        DownloadQueue queue = new DownloadQueue(dir.toFile());
        check(queue.head() == null && queue.history().size() == 1 && queue.history().get(0).record.output.equals(ROW), "legacy finished item");
    }

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
}
