<#
.SYNOPSIS
Turns an emailed crash report from a release build back into readable class and method names, using
the R8 map that scripts/release.ps1 saved for that version (~/.twodo/mappings/v<Version>-mapping.txt).

.EXAMPLE
./scripts/retrace.ps1 0.6.4 report.txt
Paste the email's text into report.txt first. The version is in the email's first line.
#>
param(
    [Parameter(Mandatory)][string]$Version,
    [Parameter(Mandatory)][string]$Report
)
$ErrorActionPreference = 'Stop'
$mapping = "$HOME/.twodo/mappings/v$Version-mapping.txt"
if (-not (Test-Path $mapping)) { throw "No map for v$Version in ~/.twodo/mappings; reports from it can't be decoded." }
if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = @(
        (Get-ChildItem 'C:\Program Files\Eclipse Adoptium\jdk-2*' -ErrorAction SilentlyContinue | Select-Object -Last 1).FullName,
        'C:\Program Files\Android\Android Studio\jbr'
    ) | Where-Object { $_ -and (Test-Path "$_\bin\java.exe") } | Select-Object -First 1
}
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
& "$sdk\cmdline-tools\latest\bin\retrace.bat" $mapping $Report
