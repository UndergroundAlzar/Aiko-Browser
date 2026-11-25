package app.aikobrowser;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 1-Tap atomic `.aiko` ZIP backup & restore engine.
 * Packs only allowlisted Aiko SharedPreferences XMLs, custom adblock rules, and custom fonts,
 * safely skipping WebView/Chromium internals and enforcing strict path-traversal & size guards.
 */
public final class BackupManager {
    public static final int FORMAT_VERSION = 1;
    public static final String APP_VERSION = "0.5.0";
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

    private BackupManager() {}

    public static String suggestedFilename() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT);
        return "AikoBackup_" + fmt.format(new Date()) + ".aiko";
    }

    /** Exports Aiko configuration & user files from the app's data directory to an OutputStream. */
    public static ExportReport exportToStream(Context ctx, OutputStream out) throws IOException {
        if (ctx == null || out == null) throw new IOException("导出参数无效");
        return exportFromDir(resolveDataDir(ctx), out);
    }

    /** Atomically exports a `.aiko` backup file on disk. */
    public static ExportReport exportToFile(Context ctx, File dest) throws IOException {
        if (ctx == null || dest == null) throw new IOException("导出路径无效");
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建备份目录");
        }
        File tmp = new File(parent != null ? parent : dest.getAbsoluteFile().getParentFile(),
                dest.getName() + "." + System.nanoTime() + ".tmp");
        try {
            ExportReport report;
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp), 65536)) {
                report = exportFromDir(resolveDataDir(ctx), out);
            }
            moveAtomic(tmp, dest);
            return report;
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
    }

    /** Restores Aiko configuration & user files into the app's data directory atomically. */
    public static RestoreReport importFromStream(Context ctx, InputStream in) throws IOException {
        if (ctx == null || in == null) throw new IOException("恢复参数无效");
        return importToDir(resolveDataDir(ctx), in);
    }

    /**
     * Pure-Java directory export to `.aiko` ZIP stream (host-testable).
     * Packs allowlisted `shared_prefs/*.xml`, `files/filters-user.txt`, `files/fonts/*`, and `manifest.json`.
     */
    public static ExportReport exportFromDir(File dataDir, OutputStream out) throws IOException {
        if (dataDir == null || out == null) throw new IOException("无效的数据目录或输出流");

        List<FileItem> items = new ArrayList<>();
        int prefsCount = 0;
        int filesCount = 0;
        long totalBytes = 0;

        File prefsDir = new File(dataDir, "shared_prefs");
        for (String name : PREF_FILES) {
            File f = new File(prefsDir, name);
            if (isSafeRegularFile(f)) {
                long len = f.length();
                if (len <= MAX_ENTRY_BYTES && totalBytes + len <= MAX_TOTAL_BYTES) {
                    items.add(new FileItem("shared_prefs/" + name, f));
                    prefsCount++;
                    totalBytes += len;
                }
            }
        }

        File filesDir = new File(dataDir, "files");
        File userFilters = new File(filesDir, "filters-user.txt");
        if (isSafeRegularFile(userFilters)) {
            long len = userFilters.length();
            if (len <= MAX_ENTRY_BYTES && totalBytes + len <= MAX_TOTAL_BYTES) {
                items.add(new FileItem(USER_FILTER_ENTRY, userFilters));
                filesCount++;
                totalBytes += len;
            }
        }

        File fontsDir = new File(filesDir, "fonts");
        if (fontsDir.isDirectory()) {
            File[] fontFiles = fontsDir.listFiles();
            if (fontFiles != null) {
                Arrays.sort(fontFiles, (a, b) -> a.getName().compareTo(b.getName()));
                for (File ff : fontFiles) {
                    String safeName = StartPageConfig.sanitizeFontFilename(ff.getName());
                    if (safeName.isEmpty() || !safeName.equals(ff.getName()) || !isSafeRegularFile(ff)) {
                        continue;
                    }
                    long len = ff.length();
                    if (len <= MAX_ENTRY_BYTES && totalBytes + len <= MAX_TOTAL_BYTES
                            && items.size() < MAX_ENTRIES - 1) {
                        items.add(new FileItem(FONTS_PREFIX + safeName, ff));
                        filesCount++;
                        totalBytes += len;
                    }
                }
            }
        }

        JSONObject manifest = new JSONObject();
        byte[] manifestBytes;
        try {
            manifest.put("formatVersion", FORMAT_VERSION);
            manifest.put("appVersion", APP_VERSION);
            manifest.put("createdAt", System.currentTimeMillis());
            manifest.put("prefsCount", prefsCount);
            manifest.put("filesCount", filesCount);
            JSONArray entryNames = new JSONArray();
            for (FileItem item : items) entryNames.put(item.zipPath);
            manifest.put("entries", entryNames);
            manifestBytes = manifest.toString(2).getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new IOException("无法生成备份清单", e);
        }

        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(out, 65536));
        try {
            writeZipBytes(zos, MANIFEST_ENTRY, manifestBytes);
            byte[] buf = new byte[32768];
            for (FileItem item : items) {
                ZipEntry entry = new ZipEntry(item.zipPath);
                entry.setTime(item.file.lastModified());
                zos.putNextEntry(entry);
                try (InputStream fis = new BufferedInputStream(new FileInputStream(item.file), 32768)) {
                    int n;
                    while ((n = fis.read(buf)) >= 0) {
                        zos.write(buf, 0, n);
                    }
                }
                zos.closeEntry();
            }
            zos.finish();
            zos.flush();
        } finally {
            zos.close();
        }

        String summary = String.format(Locale.ROOT, "已导出 %d 项设置与 %d 个扩展文件", prefsCount, filesCount);
        return new ExportReport(prefsCount, filesCount, totalBytes + manifestBytes.length, summary);
    }

    /**
     * Pure-Java atomic restore from `.aiko` ZIP stream (host-testable).
     * Validates `manifest.json`, rejects path traversal and oversized entries before modifying any file.
     */
    public static RestoreReport importToDir(File dataDir, InputStream in) throws IOException {
        if (dataDir == null || in == null) throw new IOException("无效的数据目录或输入流");

        byte[] manifestBytes = null;
        LinkedHashMap<String, byte[]> staged = new LinkedHashMap<>();
        long totalRead = 0;
        int entryCount = 0;

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(in, 65536))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ENTRIES) {
                    throw new IOException("备份包内文件数量超出安全上限");
                }
                String rawName = entry.getName();
                validateEntryPath(rawName);
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }
                long declaredSize = entry.getSize();
                if (declaredSize > MAX_ENTRY_BYTES) {
                    throw new IOException("备份条目超过 20 MiB 限制: " + rawName);
                }

                byte[] content = readBounded(zis, MAX_ENTRY_BYTES, MAX_TOTAL_BYTES - totalRead, rawName);
                totalRead += content.length;
                zis.closeEntry();

                if (MANIFEST_ENTRY.equals(rawName)) {
                    manifestBytes = content;
                } else if (isAllowedRestorePath(rawName)) {
                    staged.put(rawName, content);
                }
                // Unknown safe entries outside allowlist are ignored for forward compatibility
                // without modifying WebView or system internals.
            }
        }

        if (manifestBytes == null) {
            throw new IOException("备份文件缺少 manifest.json，不是有效的 .aiko 备份包");
        }

        String appVersion;
        try {
            JSONObject manifest = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
            int formatVersion = manifest.optInt("formatVersion", -1);
            if (formatVersion != FORMAT_VERSION) {
                throw new IOException("不支持的备份格式版本: " + formatVersion);
            }
            appVersion = manifest.optString("appVersion", "unknown");
        } catch (JSONException e) {
            throw new IOException("备份清单 manifest.json 已损坏", e);
        }

        String canonicalBase = dataDir.getCanonicalPath();
        if (!canonicalBase.endsWith(File.separator)) canonicalBase += File.separator;

        int prefsRestored = 0;
        int filesRestored = 0;

        // Write all staged files via temp-file + atomic move after full archive validation passes.
        for (Map.Entry<String, byte[]> item : staged.entrySet()) {
            String relPath = item.getKey();
            File target = new File(dataDir, relPath);
            String canonicalTarget = target.getCanonicalPath();
            if (!canonicalTarget.startsWith(canonicalBase)) {
                throw new IOException("检测到非法越界路径: " + relPath);
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("无法创建恢复目录: " + parent.getName());
            }
            writeFileAtomic(target, item.getValue());
            if (relPath.startsWith("shared_prefs/")) prefsRestored++;
            else filesRestored++;
        }

        String summary = String.format(Locale.ROOT,
                "已恢复 %d 项设置与 %d 个扩展文件（来源版本 v%s）",
                prefsRestored, filesRestored, appVersion);
        return new RestoreReport(prefsRestored, filesRestored, appVersion, summary);
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
        if (relPath.startsWith(FONTS_PREFIX)) {
            String font = relPath.substring(FONTS_PREFIX.length());
            return font.indexOf('/') < 0 && !font.isEmpty()
                    && font.equals(StartPageConfig.sanitizeFontFilename(font));
        }
        return false;
    }

    private static byte[] readBounded(InputStream in, long maxEntry, long maxRemaining, String name)
            throws IOException {
        long limit = Math.min(maxEntry, Math.max(0L, maxRemaining));
        ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
        byte[] buf = new byte[16384];
        long count = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            count += n;
            if (count > limit) {
                throw new IOException("备份条目超过安全体积上限: " + name);
            }
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    private static void writeZipBytes(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    private static void writeFileAtomic(File target, byte[] bytes) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + "." + System.nanoTime() + ".aiko.tmp");
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(bytes);
                fos.flush();
                fos.getFD().sync();
            }
            moveAtomic(tmp, target);
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
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
        final String zipPath;
        final File file;

        FileItem(String zipPath, File file) {
            this.zipPath = zipPath;
            this.file = file;
        }
    }
}
