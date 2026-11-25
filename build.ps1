[CmdletBinding()]
param(
    [string] $JavaHome = $env:JAVA_HOME,
    [string] $SdkRoot = $env:ANDROID_HOME,
    [string] $AndroidJar,
    [string] $BuildTools,
    [string] $SigningKey = (Join-Path $PSScriptRoot 'build/local-preview.p12'),
    [string] $SigningAlias = 'aiko-preview',
    [string] $OutputFile = (Join-Path $PSScriptRoot 'build/Aiko-Browser-0.5.0.apk')
)
$ErrorActionPreference = 'Stop'
if (-not $AndroidJar -and $SdkRoot) { $AndroidJar = Join-Path $SdkRoot 'platforms/android-36/android.jar' }
if (-not $BuildTools -and $SdkRoot) { $BuildTools = Join-Path $SdkRoot 'build-tools/36.0.0' }
if (-not $JavaHome -or -not $AndroidJar -or -not $BuildTools) {
    throw 'Set JAVA_HOME and ANDROID_HOME or pass -JavaHome, -AndroidJar and -BuildTools.'
}
$JavaHome = (Resolve-Path -LiteralPath $JavaHome).Path
$AndroidJar = (Resolve-Path -LiteralPath $AndroidJar).Path
$BuildTools = (Resolve-Path -LiteralPath $BuildTools).Path
$SigningKey = [IO.Path]::GetFullPath($SigningKey)
$OutputFile = [IO.Path]::GetFullPath($OutputFile)
$primary = Join-Path $PSScriptRoot 'app/src/main'
$build = Join-Path $PSScriptRoot ('build/build-' + [Guid]::NewGuid().ToString('N'))
$classes = Join-Path $build 'classes'
$generated = Join-Path $build 'generated'
$dex = Join-Path $build 'dex'
foreach ($dir in @($classes,$generated,$dex,[IO.Path]::GetDirectoryName($SigningKey),[IO.Path]::GetDirectoryName($OutputFile))) {
    [IO.Directory]::CreateDirectory($dir) | Out-Null
}
function Run-Native {
    param([string]$Program, [string[]]$ArgList)
    & $Program @ArgList
    if ($LASTEXITCODE -ne 0) { throw "$([IO.Path]::GetFileName($Program)) failed with exit code $LASTEXITCODE" }
}
function Get-Dependency {
    param([string]$Url, [string]$Hash)
    $cache = Join-Path $PSScriptRoot 'build/deps'
    [IO.Directory]::CreateDirectory($cache) | Out-Null
    $target = Join-Path $cache ([IO.Path]::GetFileName($Url))
    if ((Test-Path -LiteralPath $target) -and (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ieq $Hash) {
        return $target
    }
    $temporary = $target + '.' + [Guid]::NewGuid().ToString('N') + '.download'
    try {
        Invoke-WebRequest -Uri $Url -OutFile $temporary -TimeoutSec 40
        if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash -ine $Hash) {
            throw 'AndroidX dependency checksum mismatch'
        }
        Move-Item -LiteralPath $temporary -Destination $target -Force
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
    }
    return $target
}
function Expand-AarEntry {
    param([string]$Archive, [string]$EntryName, [string]$File)
    $zip = [IO.Compression.ZipFile]::OpenRead($Archive)
    try {
        $entry = $zip.GetEntry($EntryName)
        if (-not $entry) { throw "Missing $EntryName in $Archive" }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $File, $false)
    } finally {
        $zip.Dispose()
    }
}
$maven = 'https://dl.google.com/dl/android/maven2/androidx'
$webkitAar = Get-Dependency "$maven/webkit/webkit/1.18.0/webkit-1.18.0.aar" 'f15a0bd7d51f4bb8dffdd3a2cf5829f9a7de9ad4ba2682fa85d619032050581f'
$coreAar = Get-Dependency "$maven/core/core/1.1.0/core-1.1.0.aar" '76c7cfbe596fe3c09a6983bf1c89e889299c08ac9a3b52ce5182a088d056647e'
$annotations = Get-Dependency "$maven/annotation/annotation-jvm/1.8.1/annotation-jvm-1.8.1.jar" '9aab326d9492800991854360ac248f493ce7f7c3183519309b78ace9e240f6f6'
$experimentalAar = Get-Dependency "$maven/annotation/annotation-experimental/1.3.0/annotation-experimental-1.3.0.aar" 'abfd29c8556e5bd0325a9f769ab9e9d154ff4a5515c476cdd5a2a8285b1b19dc'
$jspecify = Get-Dependency 'https://repo.maven.apache.org/maven2/org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar' '1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab'
$kotlinAnnotations = Get-Dependency 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.1.20/kotlin-stdlib-2.1.20.jar' '1bcc74e8ce84e2c25eaafde10f1248349cce3062b6e36978cbeec610db1e930a'
$webkitJar = Join-Path $build 'webkit.jar'
$coreJar = Join-Path $build 'core.jar'
$experimentalJar = Join-Path $build 'annotation-experimental.jar'
$webkitKeep = Join-Path $build 'webkit-keep.pro'
Expand-AarEntry $webkitAar 'classes.jar' $webkitJar
Expand-AarEntry $coreAar 'classes.jar' $coreJar
Expand-AarEntry $experimentalAar 'classes.jar' $experimentalJar
Expand-AarEntry $webkitAar 'proguard.txt' $webkitKeep
$java = Join-Path $JavaHome 'bin/java.exe'
$javac = Join-Path $JavaHome 'bin/javac.exe'
$jar = Join-Path $JavaHome 'bin/jar.exe'
$keytool = Join-Path $JavaHome 'bin/keytool.exe'
$aapt = Join-Path $BuildTools 'aapt2.exe'
$zipalign = Join-Path $BuildTools 'zipalign.exe'
$res = Join-Path $build 'resources.zip'
$unsigned = Join-Path $build 'unsigned.apk'
$aligned = Join-Path $build 'aligned.apk'
$classJar = Join-Path $build 'classes.jar'
$aaptKeep = Join-Path $build 'aapt-keep.pro'
Run-Native $aapt @('compile','--dir',"$primary/res",'-o',$res)
Run-Native $aapt @('link','-I',$AndroidJar,'--manifest',"$primary/AndroidManifest.xml",'--java',$generated,
    '--proguard',$aaptKeep,'-A',"$primary/assets",'-o',$unsigned,$res)
$sources = @(Get-ChildItem -Path "$primary/java",$generated -Recurse -Filter '*.java' | ForEach-Object FullName)
$classpath = @($AndroidJar,$webkitJar,$coreJar,$annotations,$experimentalJar,$jspecify,$kotlinAnnotations) -join [IO.Path]::PathSeparator
Run-Native $javac (@('--release','11','-encoding','UTF-8','-classpath',$classpath,'-d',$classes) + $sources)
Run-Native $jar @('cf',$classJar,'-C',$classes,'.')
Run-Native $java @('-cp',"$BuildTools/lib/d8.jar",'com.android.tools.r8.R8','--release','--min-api','29',
    '--lib',$AndroidJar,'--classpath',$annotations,'--classpath',$experimentalJar,'--classpath',$jspecify,'--classpath',$kotlinAnnotations,
    '--pg-conf',"$PSScriptRoot/androidx-keep.pro",'--pg-conf',$aaptKeep,'--pg-conf',$webkitKeep,'--output',$dex,$classJar,$webkitJar,$coreJar)
Run-Native $jar @('uf',$unsigned,'-C',$dex,'classes.dex')

# Fix Windows aapt2.exe bug that writes backslashes into subdirectory asset paths (e.g. assets/filters\psl.txt.gz),
# which causes Android AssetManager.open("filters/psl.txt.gz") to throw FileNotFoundException on device.
$normalizedApk = Join-Path $build 'normalized.apk'
$normalizerSrc = Join-Path $build 'NormalizeApkZip.java'
@'
import java.io.*;
import java.util.*;
import java.util.zip.*;

public final class NormalizeApkZip {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        if ("normalize".equals(mode)) {
            normalize(new File(args[1]), new File(args[2]));
        } else if ("verify".equals(mode)) {
            verify(new File(args[1]), args, 2);
        } else {
            throw new IllegalArgumentException("Unknown mode: " + mode);
        }
    }

    private static void normalize(File src, File dst) throws Exception {
        byte[] buf = new byte[65536];
        int fixedCount = 0;
        try (ZipFile zf = new ZipFile(src);
             ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(dst), 65536))) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            Set<String> seen = new HashSet<>();
            while (en.hasMoreElements()) {
                ZipEntry oldEntry = en.nextElement();
                String origName = oldEntry.getName();
                String normName = origName.replace('\\', '/');
                if (!normName.equals(origName)) fixedCount++;
                if (!seen.add(normName)) continue;

                ZipEntry next = new ZipEntry(normName);
                int method = oldEntry.getMethod();
                next.setMethod(method);
                if (oldEntry.getTime() != -1L) next.setTime(oldEntry.getTime());
                if (oldEntry.getExtra() != null) next.setExtra(oldEntry.getExtra());
                if (method == ZipEntry.STORED) {
                    next.setSize(oldEntry.getSize());
                    next.setCompressedSize(oldEntry.getSize());
                    next.setCrc(oldEntry.getCrc());
                }
                zos.putNextEntry(next);
                if (!oldEntry.isDirectory()) {
                    try (InputStream in = zf.getInputStream(oldEntry)) {
                        for (int n; (n = in.read(buf)) >= 0; ) {
                            zos.write(buf, 0, n);
                        }
                    }
                }
                zos.closeEntry();
            }
        }
        System.out.println("APK ZIP normalized: fixed " + fixedCount + " backslash entry path(s).");
    }

    private static void verify(File apk, String[] args, int offset) throws Exception {
        Set<String> present = new HashSet<>();
        List<String> badPaths = new ArrayList<>();
        boolean arscStored = false;
        try (ZipFile zf = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (name.indexOf('\\') >= 0) badPaths.add(name);
                present.add(name);
                if ("resources.arsc".equals(name)) {
                    arscStored = (e.getMethod() == ZipEntry.STORED);
                }
            }
        }
        if (!badPaths.isEmpty()) {
            throw new IllegalStateException("APK contains invalid backslash ZIP entries: " + badPaths);
        }
        if (!arscStored) {
            throw new IllegalStateException("resources.arsc must be STORED (uncompressed) for Android R+.");
        }
        List<String> missing = new ArrayList<>();
        for (int i = offset; i < args.length; i++) {
            if (!present.contains(args[i])) missing.add(args[i]);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("APK is missing required forward-slash assets: " + missing);
        }
        System.out.println("PASS APK asset verification (" + present.size() + " POSIX entries, 0 backslashes, resources.arsc STORED).");
    }
}
'@ | Set-Content -LiteralPath $normalizerSrc -Encoding utf8
Run-Native $java @($normalizerSrc,'normalize',$unsigned,$normalizedApk)
Run-Native $zipalign @('-f','4',$normalizedApk,$aligned)

# Local preview keys use a random password protected by Windows for the current user.
$passwordFile = $SigningKey + '.dpapi'
$suppliedPassword = $env:AIKO_KEYSTORE_PASSWORD
try {
    if ([string]::IsNullOrEmpty($suppliedPassword)) {
        if (Test-Path -LiteralPath $passwordFile) {
            $protected = (Get-Content -LiteralPath $passwordFile -Raw).Trim()
            $secure = ConvertTo-SecureString $protected
            $env:AIKO_KEYSTORE_PASSWORD = [System.Net.NetworkCredential]::new('', $secure).Password
        } elseif (Test-Path -LiteralPath $SigningKey) {
            throw 'For an existing key, set AIKO_KEYSTORE_PASSWORD or provide its local .dpapi password file.'
        } else {
            $bytes = [byte[]]::new(32)
            [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
            $env:AIKO_KEYSTORE_PASSWORD = [Convert]::ToBase64String($bytes)
            $secure = ConvertTo-SecureString $env:AIKO_KEYSTORE_PASSWORD -AsPlainText -Force
            $secure | ConvertFrom-SecureString | Set-Content -LiteralPath $passwordFile -Encoding ascii
        }
    }
    if (-not (Test-Path -LiteralPath $SigningKey)) {
        Run-Native $keytool @('-genkeypair','-keystore',$SigningKey,'-storetype','PKCS12',
            '-storepass:env','AIKO_KEYSTORE_PASSWORD','-keypass:env','AIKO_KEYSTORE_PASSWORD',
            '-alias',$SigningAlias,'-keyalg','RSA','-keysize','2048','-validity','10000',
            '-dname','CN=Aiko Browser local preview')
    }
    Run-Native $java @('-jar',"$BuildTools/lib/apksigner.jar",'sign','--ks',$SigningKey,'--ks-key-alias',$SigningAlias,
        '--ks-pass','env:AIKO_KEYSTORE_PASSWORD','--key-pass','env:AIKO_KEYSTORE_PASSWORD',
        '--v4-signing-enabled','false','--out',$OutputFile,$aligned)
} finally {
    if ($null -eq $suppliedPassword) {
        [Environment]::SetEnvironmentVariable('AIKO_KEYSTORE_PASSWORD', $null, 'Process')
    } else {
        $env:AIKO_KEYSTORE_PASSWORD = $suppliedPassword
    }
}
Run-Native $zipalign @('-c','4',$OutputFile)
Run-Native $java @('-jar',"$BuildTools/lib/apksigner.jar",'verify','--verbose',$OutputFile)
$requiredAssets = [System.Collections.Generic.List[string]]@(
    Get-ChildItem -Path "$primary/assets" -Recurse -File | ForEach-Object {
        $rel = [IO.Path]::GetRelativePath($primary, $_.FullName).Replace('\', '/')
        $rel
    }
)
if (-not $requiredAssets.Contains('assets/babel/babel.js')) {
    $requiredAssets.Add('assets/babel/babel.js')
}
Run-Native $java (@($normalizerSrc,'verify',$OutputFile) + $requiredAssets.ToArray())
Get-Item -LiteralPath $OutputFile | Select-Object FullName,Length
