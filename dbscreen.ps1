# Turns the checkout in the current folder into the old-editor build: the
# database screen of the release app (project_mode false), under the package
# suffix .dbscreen, so it installs beside the debug app.
# Run it from the checkout's root. Undo with:
#   git checkout -- app/build.gradle.kts app/src/debug/res/values/bools.xml
$ErrorActionPreference = 'Stop'

function Swap([string]$file, [string]$from, [string]$to) {
	$path = Join-Path (Get-Location).Path $file
	$text = [System.IO.File]::ReadAllText($path)
	if (-not $text.Contains($from)) {
		throw "$file does not contain: $from"
	}
	[System.IO.File]::WriteAllText($path, $text.Replace($from, $to))
}

Swap 'app/build.gradle.kts' 'applicationIdSuffix = ".debug"' 'applicationIdSuffix = ".dbscreen"'
Swap 'app/src/debug/res/values/bools.xml' '<bool name="project_mode">true</bool>' '<bool name="project_mode">false</bool>'
Write-Output 'Old-editor variant set. Build it with gradlew.bat assembleDebug.'
