<#
.SYNOPSIS
Builds a signed release APK and publishes it as a GitHub Release, where Obtainium picks it up. Also
builds the signed app bundle (.aab) to upload to Google Play.
The release notes are the "## Unreleased" section of CHANGELOG.md, which becomes "## v<Version> — <date>".

.EXAMPLE
./scripts/release.ps1 0.4.0
#>
param(
    [Parameter(Mandatory)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot)

if (git status --porcelain) { throw 'Commit or stash your changes first.' }
if ((git branch --show-current) -ne 'main') { throw 'Releases are made from main.' }
if (git tag -l "v$Version") { throw "v$Version already exists." }
if (-not (Test-Path "$HOME/.twodo/keystore.properties")) { throw 'Signing key not found in ~/.twodo/.' }
if (-not $env:JAVA_HOME) {
    # Any JDK 17+ works; Android Studio ships one.
    $env:JAVA_HOME = @(
        (Get-ChildItem 'C:\Program Files\Eclipse Adoptium\jdk-2*' -ErrorAction SilentlyContinue | Select-Object -Last 1).FullName,
        'C:\Program Files\Android\Android Studio\jbr'
    ) | Where-Object { $_ -and (Test-Path "$_\bin\java.exe") } | Select-Object -First 1
    if (-not $env:JAVA_HOME) { throw 'No JDK found; set JAVA_HOME to a JDK 17+.' }
}

# Release notes: everything under "## Unreleased" up to the next version heading.
$changelog = Get-Content CHANGELOG.md -Raw
$match = [regex]::Match($changelog, '(?ms)^## Unreleased\s*\r?\n(.*?)(?=^## )')
$notes = $match.Groups[1].Value.Trim()
if (-not $match.Success -or -not $notes) { throw 'Add release notes under "## Unreleased" in CHANGELOG.md first.' }
$date = Get-Date -Format 'yyyy-MM-dd'
$changelog = $changelog.Substring(0, $match.Index) + "## Unreleased`n`n## v$Version — $date`n`n$notes`n`n" +
    $changelog.Substring($match.Index + $match.Length)
Set-Content CHANGELOG.md $changelog.TrimEnd() -NoNewline
Add-Content CHANGELOG.md ''

# versionName comes from the argument; versionCode just has to keep increasing.
$props = Get-Content gradle.properties
$code = 1 + [int]($props | Select-String '^intack\.versionCode=(\d+)').Matches[0].Groups[1].Value
$props = $props -replace '^intack\.versionName=.*', "intack.versionName=$Version" `
                -replace '^intack\.versionCode=.*', "intack.versionCode=$code"
Set-Content gradle.properties $props

./gradlew testDebugUnitTest assembleRelease bundleRelease
if ($LASTEXITCODE) {
    git checkout -- gradle.properties CHANGELOG.md
    throw 'Build failed; version and changelog left unchanged.'
}

$apk = "app/build/outputs/apk/release/Intack-v$Version.apk"
Copy-Item app/build/outputs/apk/release/app-release.apk $apk -Force
$bundle = "app/build/outputs/bundle/release/Intack-v$Version.aab"
Copy-Item app/build/outputs/bundle/release/app-release.aab $bundle -Force

git commit -q -am "Release v$Version"
git tag -a "v$Version" -m "Intack v$Version"
git push origin main "v$Version"
if ($LASTEXITCODE) { throw 'Push failed; the release commit and tag are local only.' }

$notesFile = New-TemporaryFile
Set-Content $notesFile $notes
gh release create "v$Version" $apk --title "Intack v$Version" --notes-file $notesFile
Remove-Item $notesFile
if ($LASTEXITCODE) { throw 'Creating the GitHub release failed; retry with: gh release create ...' }
Write-Host "Released v$Version (versionCode $code)."
Write-Host "For Google Play, upload: $bundle"
