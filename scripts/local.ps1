param(
    [Parameter(Position = 0)]
    [string]$Command = "help",

    [Parameter(Position = 1)]
    [string]$Argument = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$bash = Get-Command bash -ErrorAction SilentlyContinue

if ($null -eq $bash) {
    Write-Error "Git Bash 또는 WSL2의 bash가 필요합니다. 설치 후 같은 명령을 다시 실행하세요."
    exit 1
}

Push-Location $repoRoot
try {
    $arguments = @("scripts/local.sh", $Command)
    if (-not [string]::IsNullOrWhiteSpace($Argument)) {
        $arguments += $Argument
    }
    & $bash.Source @arguments
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
