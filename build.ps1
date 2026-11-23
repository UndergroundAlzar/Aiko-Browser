[CmdletBinding()]
param(
    [string] $JavaHome = $env:JAVA_HOME,
    [string] $SdkRoot = $env:ANDROID_HOME,
    [string] $AndroidJar,
    [string] $BuildTools,
    [string] $SigningKey = (Join-Path $PSScriptRoot 'build/local-preview.p12'),
    [string] $SigningAlias = 'aiko-preview',
    [string] $OutputFile = (Join-Path $PSScriptRoot 'build/Aiko-Browser-0.1.7.apk')
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
Run-Native $aapt @('compile','--dir',"$primary/res",'-o',$res)
Run-Native $aapt @('link','-I',$AndroidJar,'--manifest',"$primary/AndroidManifest.xml",'--java',$generated,
    '-A',"$primary/assets",'-o',$unsigned,$res)
$sources = @(Get-ChildItem -Path "$primary/java",$generated -Recurse -Filter '*.java' | ForEach-Object FullName)
Run-Native $javac (@('--release','11','-encoding','UTF-8','-classpath',$AndroidJar,'-d',$classes) + $sources)
Run-Native $jar @('cf',$classJar,'-C',$classes,'.')
Run-Native $java @('-cp',"$BuildTools/lib/d8.jar",'com.android.tools.r8.D8','--release','--min-api','29',
    '--lib',$AndroidJar,'--output',$dex,$classJar)
Run-Native $jar @('uf',$unsigned,'-C',$dex,'classes.dex')
Run-Native $zipalign @('-f','4',$unsigned,$aligned)

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
Get-Item -LiteralPath $OutputFile | Select-Object FullName,Length
