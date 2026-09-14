# 本机单元测试运行脚本
# 优先使用系统环境变量 JAVA_HOME / ANDROID_HOME；未设置、路径无效或 JDK 版本不满足
# 本项目构建要求（AGP 8.2 需 JVM 11+，Gradle 8.11 支持 JVM 17~23）时回退到本机探测路径
$fallbackJdk = "$env:USERPROFILE\.jdks\jbr-21.0.11"
$fallbackSdk = "$env:LOCALAPPDATA\Android\Sdk"
$fallbackGradle = "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.11.1-bin\bpt9gzteqjrbo1mjrsomdt32c\gradle-8.11.1\bin\gradle.bat"

# 校验 JDK 路径存在且 java 主版本在 [17, 23] 区间（兼容 1.8 与 21 两种版本号格式）
function Test-JdkCompatible([string]$jdkPath) {
    if (-not $jdkPath) { return $false }
    $javaExe = Join-Path $jdkPath "bin\java.exe"
    if (-not (Test-Path $javaExe)) { return $false }
    $verLine = & $javaExe -version 2>&1 | Select-Object -First 1
    if ($verLine -match '"(\d+)(?:\.(\d+))?') {
        $major = [int]$Matches[1]
        if ($major -eq 1 -and $Matches[2]) { $major = [int]$Matches[2] } # 1.8 格式取次版本号
        return ($major -ge 17 -and $major -le 23)
    }
    return $false
}

if (-not (Test-JdkCompatible $env:JAVA_HOME)) {
    Write-Output "JAVA_HOME 未设置/路径无效/版本不在 17~23 区间，回退至 $fallbackJdk"
    $env:JAVA_HOME = $fallbackJdk
}
if (-not $env:ANDROID_HOME -or -not (Test-Path $env:ANDROID_HOME)) { $env:ANDROID_HOME = $fallbackSdk }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME

if (Get-Command gradle -ErrorAction SilentlyContinue) {
    $gradle = "gradle"
} elseif (Test-Path $fallbackGradle) {
    $gradle = $fallbackGradle
} else {
    throw "未找到可用的 Gradle：PATH 中无 gradle，且 wrapper 缓存路径不存在，请先执行一次 Gradle 同步"
}

Set-Location e:\autoDO
& $gradle testDebugUnitTest --console=plain 2>&1 | Select-Object -Last 50
Write-Output "EXITCODE=$LASTEXITCODE"
