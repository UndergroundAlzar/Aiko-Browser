package app.aikobrowser;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.Enumeration;
import java.util.zip.ZipOutputStream;

/**
 * Portable v1 ZIP and password-protected .aiko backups. Imports validate and stage
 * the archive before changing live preferences or app-private files.
 */
public final class BackupManager {
    public static final int FORMAT_VERSION = 1;
    public static final String APP_VERSION = "0.5.1";
    public static final String MANIFEST_ENTRY = "manifest.json";
    public static final long MAX_ENTRY_BYTES = 20L * 1024 * 1024;  // 20 MiB per entry
    public static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;  // 64 MiB total uncompressed
    public static final int MAX_ENTRIES = 128;

    private static final String[] PREF_FILES = {
            "aiko_theme.xml",
            "perf_profiles.xml",
            "library.xml",
            "site_filter.xml",
            "site_profiles.xml",
            "site_extras.xml",
            "display_mode.xml",
            "userscripts.xml",
            "userscript_values.xml",
            "userscript_connects.xml",
            "search_engines.xml",
            "reading_shelf.xml",
            "reader_prefs.xml",
            "babel_config.xml",
            "start_page_config.xml",
            "aiko_bangs.xml",
            "menu_config.xml"
    };

    private static final Set<String> ALLOWED_PREFS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(PREF_FILES)));

    private static final String USER_FILTER_ENTRY = "files/filters-user.txt";
    private static final String FONTS_PREFIX = "files/fonts/";

    public static final class ExportReport {
        public final int prefsExported;
        public final int filesExported;
        public final long totalBytes;
        public final String summary;

        public ExportReport(int prefsExported, int filesExported, long totalBytes, String summary) {
            this.prefsExported = prefsExported;
            this.filesExported = filesExported;
            this.totalBytes = totalBytes;
            this.summary = summary;
        }
    }

    public static final class RestoreReport {
        public final int prefsRestored;
        public final int filesRestored;
        public final String appVersion;
        public final String summary;

        public RestoreReport(int prefsRestored, int filesRestored, String appVersion, String summary) {
            this.prefsRestored = prefsRestored;
            this.filesRestored = filesRestored;
            this.appVersion = appVersion;
            this.summary = summary;
        }
    }

    static final class RestoreControl {
        private boolean canceled, applying;

        synchronized boolean cancel() {
            if (applying) return false;
            canceled = true;
            return true;
        }

        synchronized void check() throws java.io.InterruptedIOException {
            if (canceled) throw new java.io.InterruptedIOException();
        }

        synchronized void beginApply() throws java.io.InterruptedIOException {
            check();
            applying = true;
        }
    }

    private BackupManager() {}

    public static String suggestedFilename() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT);
        return "AikoBackup_" + fmt.format(new Date()) + ".aiko";
    }

    public static ExportReport exportToStream(Context ctx, OutputStream out) throws IOException {
        return exportToStream(ctx, out, null);
    }

    public static ExportReport exportToStream(Context ctx, OutputStream out, char[] password) throws IOException {
        if (ctx == null) throw new IOException("无效的备份参数");
        return export(ctx, resolveDataDir(ctx), ctx.getCacheDir(), out, password);
    }

    public static ExportReport exportToFile(Context ctx, File dest) throws IOException {
        if (ctx == null || dest == null) throw new IOException("无效的备份参数");
        File parent = dest.getAbsoluteFile().getParentFile();
        Files.createDirectories(parent.toPath());
        File tmp = File.createTempFile(".aiko-", ".tmp", parent);
        try {
            ExportReport report;
            try (OutputStream out = new FileOutputStream(tmp)) { report = exportToStream(ctx, out); }
            moveAtomic(tmp, dest);
            return report;
        } finally { tmp.delete(); }
    }

    public static RestoreReport importFromStream(Context ctx, InputStream in) throws IOException {
        return importFromStream(ctx, in, null);
    }

    public static RestoreReport importFromStream(Context ctx, InputStream in, char[] password) throws IOException {
        return importFromStream(ctx, in, password, new RestoreControl());
    }

    static RestoreReport importFromStream(Context ctx, InputStream in, char[] password, RestoreControl control) throws IOException {
        if (ctx == null) throw new IOException("无效的恢复参数");
        return restore(ctx, resolveDataDir(ctx), ctx.getCacheDir(), in, password, control);
    }

    public static ExportReport exportFromDir(File root, OutputStream out) throws IOException {
        return exportFromDir(root, out, null);
    }

    static ExportReport exportFromDir(File root, OutputStream out, char[] password) throws IOException {
        return export(null, root, null, out, password);
    }

    public static RestoreReport importToDir(File root, InputStream in) throws IOException {
        return importToDir(root, in, null);
    }

    static RestoreReport importToDir(File root, InputStream in, char[] password) throws IOException {
        return restore(null, root, null, in, password, new RestoreControl());
    }

    private static SharedPreferences prefs(Context ctx, String path) {
        return ctx.getSharedPreferences(path.substring("shared_prefs/".length(), path.length() - 4), Context.MODE_PRIVATE);
    }

    private static File target(File root, String path) throws IOException {
        File result = new File(root.getCanonicalFile(), path);
        if (!result.getCanonicalFile().equals(result) || (result.exists() && !result.isFile())) {
            throw new IOException("备份路径无效");
        }
        return result;
    }

    private static Map<String,Object> currentPrefs(Context ctx, File root, String path) throws IOException {
        File file = target(root, path);
        if (ctx != null) {
            Map<String,Object> values = new LinkedHashMap<>(prefs(ctx, path).getAll());
            return file.exists() || !values.isEmpty() ? values : null;
        }
        if (!file.isFile()) return null;
        if (file.length() > MAX_ENTRY_BYTES) throw new IOException("设置文件过大");
        return BackupPreferences.read(Files.readAllBytes(file.toPath()));
    }

    private static final int MANIFEST_LIMIT = 32 * 1024;
    private static final class ExportPlan {
        final Map<String,FileItem> entries = new LinkedHashMap<>();
        long bytes;
        int prefsCount, skippedBodies;

        boolean fits(long size) {
            return size <= MAX_ENTRY_BYTES && bytes + size <= MAX_TOTAL_BYTES - MANIFEST_LIMIT
                    && entries.size() < MAX_ENTRIES - 1;
        }

        void pref(String path, Map<String,Object> values) throws IOException {
            byte[] xml = BackupPreferences.encode(values);
            if (!fits(xml.length)) throw new IOException("设置超出备份容量限制");
            entries.put(path, new FileItem(null, xml));
            bytes += xml.length;
            prefsCount++;
        }

        boolean file(String name, File f) {
            if (entries.containsKey(name)) return true;
            if (!isSafeRegularFile(f) || !fits(f.length())) return false;
            FileItem item = new FileItem(f, null);
            entries.put(name, item);
            bytes += item.size;
            return true;
        }
    }

    private static synchronized ExportReport export(Context ctx, File root, File tempDir, OutputStream out, char[] password) throws IOException {
        if (root == null || out == null) throw new IOException("无效的备份参数");
        if (password != null && password.length < 8) throw new IOException("备份密码至少需要 8 个字符");
        ExportPlan plan = new ExportPlan();
        Map<String,Object> shelf = null;
        for (String name : PREF_FILES) {
            String path = "shared_prefs/" + name;
            Map<String,Object> values = currentPrefs(ctx, root, path);
            if (values == null) continue;
            if (path.equals(BackupPreferences.SHELF)) { shelf = values; continue; }
            if (path.equals(BackupPreferences.BABEL) && password == null) BackupPreferences.portableBabel(values, null);
            plan.pref(path, values);
        }
        // Reserve both one archive entry and the index size before optional assets fill the budget.
        int reserved = 0;
        if (shelf != null) {
            reserved = BackupPreferences.encode(shelf).length;
            if (!plan.fits(reserved)) throw new IOException("书架索引超出容量限制");
            plan.bytes += reserved;
            plan.entries.put(BackupPreferences.SHELF, new FileItem(null, new byte[0]));
        }
        plan.file(USER_FILTER_ENTRY, target(root, USER_FILTER_ENTRY));
        File[] fonts = new File(root, "files/fonts").listFiles();
        if (fonts != null) {
            Arrays.sort(fonts, (a,b) -> a.getName().compareTo(b.getName()));
            for (File font : fonts) {
                String path = FONTS_PREFIX + font.getName();
                if (isAllowedRestorePath(path) && isSafeRegularFile(font)) plan.file(path, target(root, path));
            }
        }
        if (shelf != null) {
            BackupPreferences.rebaseShelf(shelf, filename -> {
                String path = BackupPreferences.BODIES + filename;
                if (plan.file(path, target(root, path))) return filename;
                plan.skippedBodies++;
                return null;
            });
            plan.entries.remove(BackupPreferences.SHELF);
            plan.bytes -= reserved;
            plan.pref(BackupPreferences.SHELF, shelf);
        }

        int fileCount = plan.entries.size() - plan.prefsCount;
        byte[] manifest;
        try {
            JSONObject m = new JSONObject().put("formatVersion", FORMAT_VERSION).put("appVersion", APP_VERSION)
                    .put("createdAt", System.currentTimeMillis()).put("prefsCount", plan.prefsCount)
                    .put("filesCount", fileCount).put("entries", new JSONArray(plan.entries.keySet()));
            manifest = m.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException invalid) { throw new IOException("无法生成备份清单"); }
        if (manifest.length > MANIFEST_LIMIT) throw new IOException("备份清单过大");
        File tmp = File.createTempFile("aiko-export-", ".tmp", tempDir);
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
                writeZipBytes(zip, MANIFEST_ENTRY, manifest);
                for (Map.Entry<String,FileItem> entry : plan.entries.entrySet()) {
                    FileItem item = entry.getValue();
                    if (item.xml != null) writeZipBytes(zip, entry.getKey(), item.xml);
                    else {
                        zip.putNextEntry(new ZipEntry(entry.getKey()));
                        try (InputStream source = new FileInputStream(item.file)) {
                            if (copyBounded(source, zip, item.size) != item.size) throw new IOException("文件在备份期间发生变化");
                        }
                        zip.closeEntry();
                    }
                }
            }
            if (password == null) Files.copy(tmp.toPath(), out);
            else BackupEnvelope.encrypt(tmp, out, password);
            out.flush();
        } finally { tmp.delete(); }
        String summary = String.format(Locale.ROOT, "已导出 %d 项设置与 %d 个文件", plan.prefsCount, fileCount);
        if (plan.skippedBodies > 0) summary += "；" + plan.skippedBodies + " 个离线正文缺失或超限，仅保留书架链接";
        return new ExportReport(plan.prefsCount, fileCount, plan.bytes + manifest.length, summary);
    }

    static synchronized RestoreReport restore(Context ctx, File root, File tempDir, InputStream in, char[] password, RestoreControl control) throws IOException {
        if (root == null || in == null) throw new IOException("无效的恢复参数");
        File tmp = File.createTempFile("aiko-import-", ".tmp", tempDir);
        try {
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
                InputStream cancellable = new java.io.FilterInputStream(in) {
                    @Override public int read(byte[] buf, int offset, int size) throws IOException {
                        control.check();
                        return super.read(buf, offset, size);
                    }
                };
                BackupEnvelope.unpack(new BufferedInputStream(cancellable), out, password);
            }
            return restoreZip(ctx, root, tempDir, tmp, password != null, control);
        } finally { tmp.delete(); }
    }

    private static RestoreReport restoreZip(Context ctx, File root, File tempDir, File tmp, boolean encrypted, RestoreControl control) throws IOException {
        Map<String,File> staged = new LinkedHashMap<>();
        Map<String,Map<String,Object>> maps = new LinkedHashMap<>();
        List<File> garbage = new ArrayList<>();
        String appVersion;
        try (ZipFile zip = new ZipFile(tmp)) {
            if (zip.size() > MAX_ENTRIES) throw new IOException("备份条目过多");
            Set<String> seen = new HashSet<>();
            long bytes = 0;
            byte[] manifest = null;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                control.check();
                ZipEntry entry = entries.nextElement();
                String path = entry.getName();
                validateEntryPath(path);
                if (!seen.add(path)) throw new IOException("备份条目重复");
                long size = entry.getSize();
                if (size < 0 || size > MAX_ENTRY_BYTES || (bytes += size) > MAX_TOTAL_BYTES) throw new IOException("备份超出体积限制");
                if (entry.isDirectory()) continue;
                if (!MANIFEST_ENTRY.equals(path) && !isAllowedRestorePath(path)) continue;
                File stagedFile = File.createTempFile("aiko-entry-", ".tmp", tempDir);
                garbage.add(stagedFile);
                try (CheckedInputStream source = new CheckedInputStream(zip.getInputStream(entry), new CRC32());
                     OutputStream out = new FileOutputStream(stagedFile)) {
                    if (copyBounded(source, out, size) != size || source.getChecksum().getValue() != entry.getCrc()) {
                        throw new IOException("备份文件校验失败");
                    }
                }
                if (MANIFEST_ENTRY.equals(path)) {
                    if (size > MANIFEST_LIMIT) throw new IOException("备份清单过大");
                    manifest = Files.readAllBytes(stagedFile.toPath());
                } else {
                    staged.put(path, stagedFile);
                    target(root, path); // Preflight every destination before the first write.
                    if (path.startsWith("shared_prefs/")) maps.put(path, BackupPreferences.read(Files.readAllBytes(stagedFile.toPath())));
                }
            }
            appVersion = validateManifest(manifest, seen);
            if (!encrypted && maps.containsKey(BackupPreferences.BABEL)) {
                BackupPreferences.portableBabel(maps.get(BackupPreferences.BABEL), currentPrefs(ctx, root, BackupPreferences.BABEL));
            }
            Set<String> referenced = new HashSet<>();
            if (maps.containsKey(BackupPreferences.SHELF)) {
                BackupPreferences.rebaseShelf(maps.get(BackupPreferences.SHELF), name -> {
                    String path = BackupPreferences.BODIES + name;
                    if (!staged.containsKey(path)) return null;
                    referenced.add(path);
                    return target(root, path).getAbsolutePath();
                });
            }
            staged.keySet().removeIf(path -> path.startsWith(BackupPreferences.BODIES) && !referenced.contains(path));
            applyRestore(ctx, root, tempDir, staged, maps, garbage, control);
        } finally { for (File f : garbage) f.delete(); }
        int files = staged.size() - maps.size();
        String summary = String.format(Locale.ROOT, "已恢复 %d 项设置与 %d 个文件", maps.size(), files);
        return new RestoreReport(maps.size(), files, appVersion, summary);
    }

    private static String validateManifest(byte[] bytes, Set<String> seen) throws IOException {
        if (bytes == null) throw new IOException("缺少备份清单");
        try {
            JSONObject m = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (m.getInt("formatVersion") != FORMAT_VERSION) throw new IOException("不支持的备份格式");
            if (m.has("entries")) {
                JSONArray names = m.getJSONArray("entries");
                Set<String> declared = new HashSet<>();
                for (int i = 0; i < names.length(); i++) declared.add(names.getString(i));
                seen.remove(MANIFEST_ENTRY);
                seen.removeIf(path -> path.endsWith("/"));
                if (declared.size() != names.length() || !declared.equals(seen)) throw new IOException("备份清单与内容不一致");
            }
            return m.optString("appVersion", "unknown");
        } catch (JSONException invalid) { throw new IOException("备份清单已损坏"); }
    }

    private static void applyRestore(Context ctx, File root, File tempDir, Map<String,File> staged,
                                     Map<String,Map<String,Object>> maps, List<File> garbage, RestoreControl control) throws IOException {
        Map<String,File> oldFiles = new LinkedHashMap<>();
        Map<String,Map<String,?>> oldPrefs = new LinkedHashMap<>();
        List<String> applied = new ArrayList<>();
        for (Map.Entry<String,File> item : staged.entrySet()) {
            control.check();
            String path = item.getKey();
            File file = target(root, path);
            if (ctx != null && maps.containsKey(path)) {
                oldPrefs.put(path, prefs(ctx, path).getAll());
            } else {
                if (maps.containsKey(path)) Files.write(item.getValue().toPath(), BackupPreferences.encode(maps.get(path)));
                Files.createDirectories(file.getParentFile().toPath());
                File old = null;
                if (file.exists()) {
                    old = File.createTempFile("aiko-rollback-", ".tmp", tempDir);
                    garbage.add(old);
                    Files.copy(file.toPath(), old.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                oldFiles.put(path, old);
            }
        }
        control.beginApply();
        try {
            // Install bodies before publishing indexes. Framework commits update live preferences too.
            for (String path : oldFiles.keySet()) {
                moveAtomic(staged.get(path), target(root, path));
                applied.add(path);
            }
            for (String path : oldPrefs.keySet()) {
                applied.add(path); // commit may update the process cache even if disk persistence fails.
                BackupPreferences.replace(prefs(ctx, path), maps.get(path));
            }
        } catch (IOException | RuntimeException failure) {
            Collections.reverse(applied);
            for (String path : applied) {
                try {
                    if (oldPrefs.containsKey(path)) BackupPreferences.replace(prefs(ctx, path), oldPrefs.get(path));
                    else if (oldFiles.get(path) != null) moveAtomic(oldFiles.get(path), target(root, path));
                    else Files.deleteIfExists(target(root, path).toPath());
                } catch (IOException | RuntimeException rollback) { failure.addSuppressed(rollback); }
            }
            throw failure;
        }
    }

    static void validateEntryPath(String name) throws IOException {
        if (name == null || name.isEmpty()) {
            throw new IOException("备份包包含空路径条目");
        }
        if (name.indexOf('\0') >= 0 || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0) {
            throw new IOException("备份包包含非法路径字符: " + name);
        }
        if (name.startsWith("/") || name.startsWith("~")) {
            throw new IOException("备份包禁止绝对路径: " + name);
        }
        String[] parts = name.split("/");
        for (String part : parts) {
            if ("..".equals(part) || ".".equals(part)) {
                throw new IOException("备份包包含路径穿越条目: " + name);
            }
        }
    }

    private static boolean isAllowedRestorePath(String relPath) {
        if (relPath.startsWith("shared_prefs/")) {
            String file = relPath.substring("shared_prefs/".length());
            return file.indexOf('/') < 0 && ALLOWED_PREFS.contains(file);
        }
        if (USER_FILTER_ENTRY.equals(relPath)) return true;
        if (relPath.startsWith(BackupPreferences.BODIES)) {
            return BackupPreferences.safeArchiveName(relPath.substring(BackupPreferences.BODIES.length()));
        }
        if (relPath.startsWith(FONTS_PREFIX)) {
            String font = relPath.substring(FONTS_PREFIX.length());
            return font.indexOf('/') < 0 && !font.isEmpty()
                    && font.equals(StartPageConfig.sanitizeFontFilename(font));
        }
        return false;
    }

    static long copyBounded(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buf = new byte[32768];
        long count = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            if ((count += n) > limit) throw new IOException("备份超出体积限制");
            out.write(buf, 0, n);
        }
        return count;
    }

    private static void writeZipBytes(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    private static void moveAtomic(File src, File dst) throws IOException {
        try {
            Files.move(src.toPath(), dst.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean isSafeRegularFile(File f) {
        return f != null && f.isFile() && !Files.isSymbolicLink(f.toPath());
    }

    private static File resolveDataDir(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (app.getApplicationInfo() != null && app.getApplicationInfo().dataDir != null) {
            return new File(app.getApplicationInfo().dataDir);
        }
        File files = app.getFilesDir();
        return files != null && files.getParentFile() != null ? files.getParentFile() : files;
    }

    private static final class FileItem {
        final File file;
        final byte[] xml;
        final long size;

        FileItem(File file, byte[] xml) {
            this.file = file;
            this.xml = xml;
            this.size = file == null ? xml.length : file.length();
        }
    }
}
