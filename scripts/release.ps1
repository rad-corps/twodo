<#
.SYNOPSIS
Builds a signed release APK and publishes it as a GitHub Release, where Obtainium picks it up.

.EXAMPLE
./scripts/release.ps1 0.2.0
./scripts/release.ps1 0.2.0 -Notes "Adds list renaming"
#>
param(
    [Parameter(Mandatory)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version,
    # Release notes; GitHub generates them from the commits when omitted.
    [string]$Notes
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot)

if (git status --porcelain) { throw 'Commit or stash your changes first.' }
if ((git branch --show-current) -ne 'main') { throw 'Releases are made from main.' }
if (git tag -l "v$Version") { throw "v$Version already exists." }
if (-not (Test-Path "$HOME/.twodo/keystore.properties")) { throw 'Signing key not found in ~/.twodo/.' }
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot' }

# versionName comes from the argument; versionCode just has to keep increasing.
$props = Get-Content gradle.properties
$code = 1 + [int]($props | Select-String '^twodo\.versionCode=(\d+)').Matches[0].Groups[1].Value
$props = $props -replace '^twodo\.versionName=.*', "twodo.versionName=$Version" `
                -replace '^twodo\.versionCode=.*', "twodo.versionCode=$code"
Set-Content gradle.properties $props

./gradlew testDebugUnitTest assembleRelease
if ($LASTEXITCODE) {
    git checkout -- gradle.properties
    throw 'Build failed; version left unchanged.'
}

$apk = "app/build/outputs/apk/release/TwoDo-v$Version.apk"
Copy-Item app/build/outputs/apk/release/app-release.apk $apk -Force

git commit -q -am "Release v$Version"
git tag -a "v$Version" -m "TwoDo v$Version"
git push origin main "v$Version"
if ($LASTEXITCODE) { throw 'Push failed; the release commit and tag are local only.' }

$notesArgs = if ($Notes) { @('--notes', $Notes) } else { @('--generate-notes') }
gh release create "v$Version" $apk --title "TwoDo v$Version" @notesArgs
if ($LASTEXITCODE) { throw 'Creating the GitHub release failed; retry with: gh release create ...' }
Write-Host "Released v$Version (versionCode $code)."
