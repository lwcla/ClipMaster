@echo off
setlocal

rem Keep this launcher ASCII-only because cmd.exe may parse .cmd files with the system code page.
chcp 65001 >nul

rem Launch from the repository root so relative paths in the release scripts stay stable.
pushd "%~dp0.."
if errorlevel 1 (
    echo Failed to enter repository root: %~dp0..
    if "%~1"=="" pause
    exit /b 1
)

rem The embedded PowerShell wrapper writes a full run log here for easier double-click troubleshooting.
set "release_log=build\github-gitee-release\windows-release.log"

rem Preserve the wrapped script exit code for double-click users and command-line callers.
set "release_exit_code=0"

rem Let the embedded PowerShell payload know which .cmd file hosts it.
set "CLIPMASTER_RELEASE_EMBEDDED_SCRIPT=%~f0"

rem Extract the PowerShell payload below the marker, then forward all arguments to it.
powershell -NoProfile -ExecutionPolicy Bypass -Command "$scriptPath=$env:CLIPMASTER_RELEASE_EMBEDDED_SCRIPT; $marker='@@CLIPMASTER_POWERSHELL_PAYLOAD@@'; $lines=[System.IO.File]::ReadAllLines($scriptPath,[System.Text.Encoding]::UTF8); $start=[Array]::IndexOf($lines,$marker); if($start -lt 0){ throw 'PowerShell payload marker not found.' }; $payload=[string]::Join([Environment]::NewLine,$lines[($start+1)..($lines.Length-1)]); & ([scriptblock]::Create($payload)) @args" %*
set "release_exit_code=%ERRORLEVEL%"

popd

if "%release_exit_code%"=="0" (
    echo.
    echo Release script finished.
) else (
    echo.
    echo Release script failed. Exit code: %release_exit_code%
    echo See log: %release_log%
)

echo.
rem Pause only for double-click usage; command-line checks with arguments should not block automation.
if "%~1"=="" pause
exit /b %release_exit_code%

@@CLIPMASTER_POWERSHELL_PAYLOAD@@
#Requires -Version 5.1
[CmdletBinding()]
param(
    # 原样传递给 Bash 发布脚本的参数；Windows 包装层不重新解释发布选项，避免两套入口行为分叉。
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $ReleaseArguments
)

Set-StrictMode -Version Latest

# 让 PowerShell 发现环境或文件错误时立即停止，避免继续进入真实发布流程。
$ErrorActionPreference = "Stop"

# Windows CMD 宿主脚本路径；内嵌 PowerShell 不能依赖 $PSCommandPath，必须由批处理头显式传入。
$windowsHostScriptPath = $env:CLIPMASTER_RELEASE_EMBEDDED_SCRIPT

if ([string]::IsNullOrWhiteSpace($windowsHostScriptPath)) {
    throw "找不到 Windows 发布宿主脚本路径，无法定位原始 Bash 发布脚本。"
}

# 当前包装脚本所在目录；后续用它定位同目录下的 Bash 发布脚本。
$scriptDirectory = Split-Path -Parent $windowsHostScriptPath

# 仓库根目录；用于写入 Windows 发布日志，不参与原 Bash 发布逻辑。
$repositoryRoot = Split-Path -Parent $scriptDirectory

# 原始 Bash 发布脚本路径；Windows 入口只负责转调它，发布行为仍以该脚本为准。
$releaseScriptPath = Join-Path $scriptDirectory "publish_github_release.sh"

if (-not (Test-Path -LiteralPath $releaseScriptPath -PathType Leaf)) {
    throw "找不到原始发布脚本：$releaseScriptPath"
}

# Windows 双击入口的日志目录；复用发布产物目录，方便失败后直接定位完整输出。
$windowsReleaseLogDirectory = Join-Path $repositoryRoot "build\github-gitee-release"

# Windows 双击入口的日志文件；记录本次 PowerShell 包装层和原 Bash 脚本输出。
$windowsReleaseLogPath = Join-Path $windowsReleaseLogDirectory "windows-release.log"

New-Item -ItemType Directory -Force -Path $windowsReleaseLogDirectory | Out-Null

# 无 BOM 的 UTF-8 编码；Git Bash 读取 shim、Windows 发布日志读取中文时都依赖它保持稳定。
$utf8NoBomEncoding = New-Object System.Text.UTF8Encoding($false)

# Windows 发布日志头；每次运行覆盖旧日志，避免用户读到上一次失败原因。
$windowsReleaseLogHeader = @(
    "ClipMaster Windows release log",
    "Start time: $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))",
    "Working directory: $repositoryRoot",
    "Arguments: $($ReleaseArguments -join ' ')",
    ""
)

[System.IO.File]::WriteAllLines($windowsReleaseLogPath, $windowsReleaseLogHeader, $utf8NoBomEncoding)

# Windows Program Files 路径；用于兜底查找标准 Git for Windows 安装位置。
$programFilesPath = $env:ProgramFiles

# 32 位 Program Files 路径；用 API 读取可避免括号环境变量名影响旧版 PowerShell 解析。
$programFilesX86Path = [Environment]::GetEnvironmentVariable("ProgramFiles(x86)")

# 当前用户本地应用目录；用于兼容 SourceTree 自带的 Git Bash。
$localAppDataPath = $env:LOCALAPPDATA

# PATH 中发现的 bash.exe 命令集合；数组化后可安全读取第一个候选。
$pathBashCommands = @(Get-Command bash.exe -ErrorAction SilentlyContinue)

# PATH 中第一个 bash.exe 路径；没有安装或未配置 PATH 时保留为空。
$pathBashPath = if ($pathBashCommands.Count -gt 0) { $pathBashCommands[0].Source } else { $null }

# 候选 Bash 路径原始列表；后续会过滤空值并逐一探测可用性。
$bashCandidateItems = @(
    $env:CLIPMASTER_BASH_EXE,
    $env:GIT_BASH_EXE,
    $pathBashPath
)

if (-not [string]::IsNullOrWhiteSpace($programFilesPath)) {
    # 标准 Git for Windows `bin` 目录；优先兼容用户独立安装的 Git Bash。
    $bashCandidateItems += Join-Path $programFilesPath "Git\bin\bash.exe"

    # 标准 Git for Windows `usr\bin` 目录；兼容部分安装方式把 bash 放在该目录。
    $bashCandidateItems += Join-Path $programFilesPath "Git\usr\bin\bash.exe"
}

if (-not [string]::IsNullOrWhiteSpace($programFilesX86Path)) {
    # 32 位 Git for Windows `bin` 目录；只作为非主流安装路径兜底。
    $bashCandidateItems += Join-Path $programFilesX86Path "Git\bin\bash.exe"

    # 32 位 Git for Windows `usr\bin` 目录；只作为非主流安装路径兜底。
    $bashCandidateItems += Join-Path $programFilesX86Path "Git\usr\bin\bash.exe"
}

if (-not [string]::IsNullOrWhiteSpace($localAppDataPath)) {
    # SourceTree 内置 Git Bash 路径；兼容未单独安装 Git 但安装过 SourceTree 的设备。
    $bashCandidateItems += Join-Path $localAppDataPath "Atlassian\SourceTree\git_local\bin\bash.exe"
}

# 候选 Bash 路径过滤结果；去掉空值后再进入真实命令探测。
$bashCandidates = $bashCandidateItems | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

# 最终选中的 Bash 可执行文件；为空表示本机缺少可用 Bash 环境。
$bashPath = $null

foreach ($bashCandidate in $bashCandidates) {
    # 当前候选 Bash 路径；Resolve-Path 失败时说明该候选不可用，继续检查下一个。
    $resolvedBashCandidate = Resolve-Path -LiteralPath $bashCandidate -ErrorAction SilentlyContinue

    if ($null -eq $resolvedBashCandidate) {
        continue
    }

    # 候选 Bash 版本输出；能成功返回版本号才认为它可以执行发布脚本。
    $bashVersionOutput = & $resolvedBashCandidate.ProviderPath --version 2>$null

    if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace(($bashVersionOutput | Select-Object -First 1))) {
        $bashPath = $resolvedBashCandidate.ProviderPath
        break
    }
}

if ([string]::IsNullOrWhiteSpace($bashPath)) {
    throw "找不到可用的 Git Bash。请安装 Git for Windows，或设置 CLIPMASTER_BASH_EXE 指向 bash.exe。"
}

# PATH 中发现的 python3.exe 命令集合；WindowsApps 占位入口也会被后续探测过滤。
$pathPython3Commands = @(Get-Command python3.exe -ErrorAction SilentlyContinue)

# PATH 中第一个 python3.exe 路径；没有配置时保留为空。
$pathPython3Path = if ($pathPython3Commands.Count -gt 0) { $pathPython3Commands[0].Source } else { $null }

# PATH 中发现的 python.exe 命令集合；通常是真实 Python 安装入口。
$pathPythonCommands = @(Get-Command python.exe -ErrorAction SilentlyContinue)

# PATH 中第一个 python.exe 路径；没有配置时保留为空。
$pathPythonPath = if ($pathPythonCommands.Count -gt 0) { $pathPythonCommands[0].Source } else { $null }

# PATH 中发现的 py.exe 命令集合；作为只安装 Windows Python Launcher 时的兜底。
$pathPyCommands = @(Get-Command py.exe -ErrorAction SilentlyContinue)

# PATH 中第一个 py.exe 路径；没有配置时保留为空。
$pathPyPath = if ($pathPyCommands.Count -gt 0) { $pathPyCommands[0].Source } else { $null }

# 候选 Python 路径按显式配置、真实 python3、普通 python、Windows py 启动器排序。
$pythonCandidateItems = @(
    $env:CLIPMASTER_PYTHON_EXE,
    $pathPython3Path,
    $pathPythonPath,
    $pathPyPath
)

# 候选 Python 路径过滤结果；去掉空值后再执行 Python 3 探测代码。
$pythonCandidates = $pythonCandidateItems | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

# 最终选中的 Python 可执行文件；包装层会把它映射成 Bash 可见的 python3 命令。
$pythonPath = $null

# 是否通过 Windows `py.exe` 启动器调用 Python 3；该入口需要额外追加 `-3` 参数。
$pythonUsesLauncher = $false

foreach ($pythonCandidate in $pythonCandidates) {
    # 当前候选 Python 路径；WindowsApps 占位别名或不存在的路径会在这里被跳过。
    $resolvedPythonCandidate = Resolve-Path -LiteralPath $pythonCandidate -ErrorAction SilentlyContinue

    if ($null -eq $resolvedPythonCandidate) {
        continue
    }

    # 候选 Python 文件名；用于识别是否为 `py.exe` 启动器。
    $pythonCandidateName = Split-Path -Leaf $resolvedPythonCandidate.ProviderPath

    # Python 探测参数；`py.exe` 必须指定 `-3`，普通 python/python3 直接执行即可。
    $pythonProbeArguments = @()

    if ($pythonCandidateName -ieq "py.exe") {
        $pythonProbeArguments = @("-3")
    }

    # Python 探测输出；只接受能执行 Python 3 代码的候选，避免 Windows Store 占位入口误通过。
    $pythonProbeOutput = & $resolvedPythonCandidate.ProviderPath @pythonProbeArguments -c "import sys; print(sys.version_info[0])" 2>$null

    if ($LASTEXITCODE -eq 0 -and (($pythonProbeOutput | Select-Object -First 1) -eq "3")) {
        $pythonPath = $resolvedPythonCandidate.ProviderPath
        $pythonUsesLauncher = ($pythonCandidateName -ieq "py.exe")
        break
    }
}

if ([string]::IsNullOrWhiteSpace($pythonPath)) {
    throw "找不到可用的 Python 3。请安装 Python 3，或设置 CLIPMASTER_PYTHON_EXE 指向 python.exe。"
}

# 临时兼容目录；带进程号避免多个发布窗口同时运行时互相占用 python3 shim。
$shimRoot = Join-Path $env:TEMP "clipmaster-release-tools-$PID"

New-Item -ItemType Directory -Force -Path $shimRoot | Out-Null

# Bash 可见的 python3 shim 路径；用于覆盖 WindowsApps 中不可用的 python3 占位入口。
$pythonShimPath = Join-Path $shimRoot "python3"

# python3 shim 内容；真实 Python 路径来自环境变量，避免在脚本文件中写入转义后的 Windows 路径。
$pythonShimLines = @(
    "#!/usr/bin/env bash",
    "set -euo pipefail",
    "",
    "# Windows Python 的 stdout/stderr 必须固定 UTF-8，避免中文 release body 被 Gitee 判定为非法字节。",
    "export PYTHONUTF8=1",
    "export PYTHONIOENCODING=utf-8",
    "",
    "# PowerShell 包装层把真实 Python 可执行文件写入环境变量；这里转换为 Git Bash 可识别的路径。",
    'pythonPath="$(cygpath -u "${CLIPMASTER_RELEASE_PYTHON_EXE:?}")"',
    "",
    "# Windows Python 在管道里会输出 CRLF；过滤 stdout 的 CR，避免 Bash read 把文件名读成 .apk\\r。",
    'if [[ "${CLIPMASTER_RELEASE_PYTHON_USES_LAUNCHER:-false}" == "true" ]]; then',
    '    "$pythonPath" -3 "$@" | tr -d "\r"',
    '    exit "${PIPESTATUS[0]}"',
    "fi",
    "",
    '"$pythonPath" "$@" | tr -d "\r"',
    'exit "${PIPESTATUS[0]}"'
)

[System.IO.File]::WriteAllLines($pythonShimPath, $pythonShimLines, $utf8NoBomEncoding)

# 子进程环境中的真实 Python 路径；python3 shim 读取后再转成 POSIX 路径。
$env:CLIPMASTER_RELEASE_PYTHON_EXE = $pythonPath

# 子进程环境中的 Python 启动器标记；只有选中 py.exe 时才为 true。
$env:CLIPMASTER_RELEASE_PYTHON_USES_LAUNCHER = if ($pythonUsesLauncher) { "true" } else { "false" }

# 原始 PYTHONUTF8；发布结束后恢复，避免影响用户当前 PowerShell 会话。
$previousPythonUtf8 = $env:PYTHONUTF8

# 原始 PYTHONIOENCODING；发布结束后恢复，避免影响其它 Python 命令。
$previousPythonIoEncoding = $env:PYTHONIOENCODING

# 强制 Windows Python 使用 UTF-8 模式，避免 Gitee 表单字段里的中文变成系统代码页字节。
$env:PYTHONUTF8 = "1"

# 强制 Python stdout/stderr 使用 UTF-8；Bash 会把这些输出继续交给 curl 或日志。
$env:PYTHONIOENCODING = "utf-8"

# 包装层诊断行；既打印到窗口，也写入日志，便于确认实际选中的本机工具链。
$wrapperLogLines = @(
    "使用 Bash：$bashPath",
    "使用 Python：$pythonPath",
    "转调脚本：$releaseScriptPath",
    ""
)

foreach ($wrapperLogLine in $wrapperLogLines) {
    # 单条包装层诊断输出；逐行写入可避免不同终端编码影响整块文本。
    Write-Host $wrapperLogLine
    [System.IO.File]::AppendAllText($windowsReleaseLogPath, "$wrapperLogLine`r`n", $utf8NoBomEncoding)
}

# 原始 PATH；发布子进程结束后恢复，避免污染当前 PowerShell 会话。
$previousPath = $env:PATH

# 临时把 python3 shim 放入 PATH；Git Bash login 初始化后仍会继承该目录，用于修正 WindowsApps 占位入口。
$env:PATH = "$shimRoot;$previousPath"

# 传给 bash.exe 的参数；-l 让 Git Bash 初始化自身 PATH，确保 shasum 等 Git 附带命令可用。
$bashArguments = @("-l", $releaseScriptPath) + $ReleaseArguments

# 发布脚本默认失败退出码；如果 Bash 在异常路径提前中断，finally 仍能写出稳定日志。
$releaseExitCode = 1

try {
    # Native stderr 需要作为普通输出写入日志；否则 ErrorActionPreference=Stop 会把脚本错误信息变成包装层异常。
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"

    # 直接调用原始 Bash 发布脚本；Windows 包装层只捕获输出，不接管任何发布步骤或参数语义。
    & $bashPath @bashArguments 2>&1 | ForEach-Object {
        # Bash 输出行；同步写到窗口和日志，失败时日志末尾就是原脚本真实错误。
        $bashOutputLine = $_.ToString()
        Write-Host $bashOutputLine
        [System.IO.File]::AppendAllText($windowsReleaseLogPath, "$bashOutputLine`r`n", $utf8NoBomEncoding)
    }

    # 原 Bash 发布脚本退出码；包装层保持同一退出码，方便 CI 或终端判断成功失败。
    $releaseExitCode = $LASTEXITCODE
} finally {
    # 恢复 PowerShell 错误策略，避免后续包装层清理逻辑改变全局错误处理。
    if (Get-Variable -Name previousErrorActionPreference -ErrorAction SilentlyContinue) {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    # 恢复 PATH，确保临时 python3 shim 只影响本次发布调用。
    $env:PATH = $previousPath

    # 恢复 PYTHONUTF8；原先不存在时移除，避免把包装层编码策略泄露到调用者环境。
    if ($null -eq $previousPythonUtf8) {
        Remove-Item Env:PYTHONUTF8 -ErrorAction SilentlyContinue
    } else {
        $env:PYTHONUTF8 = $previousPythonUtf8
    }

    # 恢复 PYTHONIOENCODING；原先不存在时移除，避免影响用户其它 Python 任务。
    if ($null -eq $previousPythonIoEncoding) {
        Remove-Item Env:PYTHONIOENCODING -ErrorAction SilentlyContinue
    } else {
        $env:PYTHONIOENCODING = $previousPythonIoEncoding
    }

    # 清理本次创建的临时 shim 目录；失败时不影响发布脚本自身的退出码。
    Remove-Item -LiteralPath $shimRoot -Recurse -Force -ErrorAction SilentlyContinue

    # Windows 发布日志结尾；失败时和 .cmd 摘要中的退出码保持一致。
    [System.IO.File]::AppendAllText($windowsReleaseLogPath, "Exit code: $releaseExitCode`r`n", $utf8NoBomEncoding)
}

exit $releaseExitCode
