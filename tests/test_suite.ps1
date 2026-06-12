# Scoop AI Test Suite
param(
[string]$AppFile = "test_apps.json",
[switch]$RegenerateApps = $false,
[int]$Count = 10,
[switch]$Force = $false,
[string]$Provider = "",
[string]$ApiKey = "",
[string]$BaseUrl = "",
[string]$Model = "",
[string]$Persist = "",
[string]$GitHubToken = "",
[switch]$Help = $false
)
# ================================================================
# Help
# ================================================================
if ($Help) {
Write-Host @"
Scoop AI Test Suite - Batch test manifest generation + installation
Usage: .\test_suite.ps1 [options]
Options: -Count <N>             Number of random apps to test (default: 10)
-AppFile <path>         App list JSON file (default: test_apps.json) -RegenerateApps         Re-pick random apps from Scoop buckets
-Force                  Re-test all apps (uninstall old + wipe results/logs)
 -Provider <name>        LLM provider: 
deepseek|openai|anthropic|ollama|openrouter|openai-compatible -ApiKey <key>           LLM API key
-BaseUrl <url>          API base URL (required for openai-compatible) -Model <name>           LLM model name
-Persist <mode>        Persist field policy: auto|force|none (default: auto)
 -Help                   Show this help
Provider Config (priority: CLI > config file > env vars):
1. CLI params          -Provider openai-compatible -ApiKey sk-xxx -BaseUrl http://... -Model xxx
2. Config file         tests/test_config.json
{"provider":"...","apiKey":"...","baseUrl":"...","model":"..."}
3. Env vars            SCOOP_AI_PROVIDER, SCOOP_AI_API_KEY, SCOOP_AI_BASE_URL, SCOOP_AI_MODEL
Workflow:
For each app:
1. Generate manifest via scoop-ai
2. Parse generated JSON (version/bin/checkver/autoupdate/persist)
3. Install via scoop install and check result
4. Uninstall after success
Output:
results.json   Per-app results (generation/install status, manifest fields)
logs/          Per-app stdout/stderr logs
Examples: .\test_suite.ps1 -Count 5 -Provider openai-compatible -ApiKey sk-xxx -BaseUrl 
http://localhost:8317 -Model deepseek-v4 .\test_suite.ps1 -Force -RegenerateApps -Count 3
"@
exit 0
}
$ErrorActionPreference = "Continue"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoDir = Split-Path -Parent $ScriptDir
$JarPath = Join-Path $RepoDir "cli\build\libs\cli-0.1.0-all.jar"
$Cli = "java"
$AppConfig = Join-Path $ScriptDir $AppFile
$ResultsFile = Join-Path $ScriptDir "results.json"
$LogDir = Join-Path $ScriptDir "logs"
$TestConfigFile = Join-Path $ScriptDir "test_config.json"
# ================================================================
# Resolve provider config: CLI params > test_config.json > env vars
# ================================================================
function Resolve-CliArgs {
$p = if ($Provider -ne "") { $Provider } else { $null }
$k = if ($ApiKey -ne "") { $ApiKey } else { $null }
$b = if ($BaseUrl -ne "") { $BaseUrl } else { $null }
$m = if ($Model -ne "") { $Model } else { $null }
# Fallback to test_config.json
if (($null -eq $p -or $null -eq $k) -and (Test-Path $TestConfigFile)) {
try {
$Cfg = Get-Content $TestConfigFile -Raw | ConvertFrom-Json
if ($null -eq $p -and $Cfg.provider) { $p = $Cfg.provider }
if ($null -eq $k -and $Cfg.apiKey) { $k = $Cfg.apiKey }
if ($null -eq $b -and $Cfg.baseUrl) { $b = $Cfg.baseUrl }
if ($null -eq $m -and $Cfg.model) { $m = $Cfg.model }
} catch {}
}
# Fallback to environment variables
if ($null -eq $p -and $env:SCOOP_AI_PROVIDER) { $p = $env:SCOOP_AI_PROVIDER }
if ($null -eq $k -and $env:SCOOP_AI_API_KEY) { $k = $env:SCOOP_AI_API_KEY }
if ($null -eq $b -and $env:SCOOP_AI_BASE_URL) { $b = $env:SCOOP_AI_BASE_URL }
if ($null -eq $m -and $env:SCOOP_AI_MODEL) { $m = $env:SCOOP_AI_MODEL }
# GitHub token: CLI arg > env var
$ghToken = if ($GitHubToken -ne "") { $GitHubToken } elseif ($env:GITHUB_TOKEN) { $env:GITHUB_TOKEN } else { $null }
# Build CLI argument list
$args = @()
if ($p) { $args += "-P"; $args += $p }
if ($k) { $args += "--api-key"; $args += $k }
if ($b) { $args += "--base-url"; $args += $b }
if ($m) { $args += "-m"; $args += $m }
# Always run in non-interactive mode for batch testing
$args += "-n"
# Persist mode (auto/force/none)
if ($Persist -ne "") { $args += "-s"; $args += $Persist }
if ($ghToken) { $args += "--github-token"; $args += $ghToken }
return $args
}
$CliBaseArgs = Resolve-CliArgs
# ================================================================
# Official manifest comparison helpers
# ================================================================
$OfficialBucketDirs = @(
"$env:USERPROFILE\scoop\buckets\main\bucket",
"$env:USERPROFILE\scoop\buckets\extras\bucket"
)
function Find-OfficialManifest($appName, $installedName) {
# Try app name, then installed name (AI may use different filename)
$candidates = @($appName, $installedName)
if ($appName -ne $installedName) { $candidates += $installedName }
foreach ($dir in $OfficialBucketDirs) {
if (-not (Test-Path $dir)) { continue }
foreach ($name in $candidates) {
$path = Join-Path $dir "$name.json"
if (Test-Path $path) {
try {
return @{
found = $true
source = if ($dir -match 'main') { 'main' } else { 'extras' }
path = $path
data = Get-Content $path -Raw | ConvertFrom-Json
}
} catch { return @{ found = $false; source = ''; path = ''; data = $null } }
}
}
}
return @{ found = $false; source = ''; path = ''; data = $null }
}
function Normalize-Field($value) {
# Normalize for comparison: sort lists, convert string to single-element array
if ($null -eq $value) { return $null }
if ($value -is [string]) { return @($value) }
if ($value -is [array]) {
$sorted = $value | ForEach-Object {
if ($_ -is [array]) { ($_ -join '||') } else { $_.ToString() }
} | Sort-Object
return $sorted -join '|'
}
return $value.ToString()
}
function Compare-Field($aiVal, $offVal, $fieldName) {
$aiNorm = Normalize-Field $aiVal
$offNorm = Normalize-Field $offVal
if ($null -eq $aiNorm -and $null -eq $offNorm) { return 'SAME' }
if ($null -eq $aiNorm -and $null -ne $offNorm) { return 'MISSING' }
if ($null -ne $aiNorm -and $null -eq $offNorm) { return 'EXTRA' }
if ($aiNorm -eq $offNorm) { return 'SAME' }
return 'DIFF'
}
function Compare-ManifestWithOfficial($aiData, $official) {
if (-not $official.found) {
return @{ official_found = $false; official_source = ''; diffs = @{}; diff_count = 0 }
}
$off = $official.data
$diffs = @{}
$count = 0
$fields = @('version', 'description', 'homepage', 'license', 'bin', 'persist', 
'extract_dir', 'checkver', 'innosetup')
# Complex fields: compare as JSON strings
$complexFields = @('architecture', 'autoupdate', 'shortcuts', 'pre_install', 'env_set', 
'suggest')
foreach ($f in $fields) {
$result = Compare-Field $aiData.$f $off.$f $f
if ($result -ne 'SAME') {
$diffs[$f] = @{
status = $result
ai = $aiData.$f | ConvertTo-Json -Compress -Depth 2
official = $off.$f | ConvertTo-Json -Compress -Depth 2
}
$count++
}
}
foreach ($f in $complexFields) {
$aiJson = if ($null -ne $aiData.$f) { $aiData.$f | ConvertTo-Json -Compress -Depth 3 } 
else { $null }
$offJson = if ($null -ne $off.$f) { $off.$f | ConvertTo-Json -Compress -Depth 3 } else { 
$null }
$result = Compare-Field $aiJson $offJson $f
if ($result -ne 'SAME') {
$diffs[$f] = @{
status = $result
ai = $aiJson
official = $offJson
}
$count++
}
}
return @{
official_found = $true
official_source = $official.source
diffs = $diffs
diff_count = $count
}
}
# ================================================================
# URL size check
# ================================================================
# Validate: need at least provider + apiKey (unless ollama)
$EffProvider = if ($Provider -ne "") { $Provider } elseif (Test-Path $TestConfigFile) {
try { (Get-Content $TestConfigFile -Raw | ConvertFrom-Json).provider } catch { $null }
} else { $env:SCOOP_AI_PROVIDER }
if (-not $EffProvider -and $CliBaseArgs -notcontains "-P") {
# No provider at all 鈥?check if env vars or config exist
if (-not (Test-Path $TestConfigFile) -and -not $env:SCOOP_AI_API_KEY -and -not 
$env:DEEPSEEK_API_KEY) {
Write-Host "ERROR: No provider config found. Use one of:" -ForegroundColor Red
Write-Host "  1. CLI params: -Provider <name> -ApiKey <key> [-BaseUrl <url>] [-Model 
<name>]" -ForegroundColor Gray
Write-Host "  2. Config file: $TestConfigFile" -ForegroundColor Gray
Write-Host "  3. Env vars:    SCOOP_AI_PROVIDER, SCOOP_AI_API_KEY (or DEEPSEEK_API_KEY)"  -ForegroundColor Gray
exit 1
}
}
# ================================================================
# Force re-test: uninstall old successes and wipe results/logs
# ================================================================
if ($Force) {
if (Test-Path $ResultsFile) {
try {
$OldLoaded = Get-Content $ResultsFile -Raw | ConvertFrom-Json
$OldResults = if ($OldLoaded -is [array]) { $OldLoaded } else { @($OldLoaded) }
$OldResults | Where-Object { $_.install_status -eq "SUCCESS" } | ForEach-Object {
$appName = if ($_.installed_app_name) { $_.installed_app_name } else { $_.app }
Write-Host "[force] Uninstalling $appName..." -ForegroundColor Yellow; & scoop uninstall $appName 2>&1 | Out-Null
}
} catch {}
Remove-Item $ResultsFile -Force
}
if (Test-Path $LogDir) { Remove-Item "$LogDir\*" -Force }
}
# ================================================================
# App list generation: randomly picks N apps from Scoop buckets
# ================================================================
function New-TestApps($TargetCount) {
$ScoopDir = "$env:USERPROFILE\scoop\buckets"
$MainBucket = Join-Path $ScoopDir "main\bucket"
$ExtrasBucket = Join-Path $ScoopDir "extras\bucket"
$Apps = @()
foreach ($Dir in @($MainBucket, $ExtrasBucket)) {
if (-not (Test-Path $Dir)) { continue }
Get-ChildItem $Dir -Filter *.json | ForEach-Object {
try {
$Data = Get-Content $_.FullName -Raw | ConvertFrom-Json
$CV = $Data.checkver
if ($CV -is [string] -and $CV -eq "github") {
# "checkver": "github" 鈥?uses bucket's default org mapping, skip
return
}
# Only pick apps with explicit GitHub URL
if ($CV -isnot [PSCustomObject] -and $CV -isnot [hashtable]) { return }
$GH = $CV.github
if (-not $GH -or $GH.Length -le 5) { return }
$Name = $_.BaseName
# Handle checkver.github that may be a repo URL or API URL
$Url = $GH -replace '^https?://github\.com/', ''
$Url = $Url.TrimEnd('/')
if ($Url -match '^https?://api\.github\.com/repos/([^/]+)/([^/]+)') {
$Url = "https://github.com/$($matches[1])/$($matches[2])"
} else {
$Url = "https://github.com/$Url"
}
$Apps += @{ name = $Name; url = $Url }
} catch { }
}
}
if ($Apps.Count -eq 0) {
Write-Host "ERROR: No apps found in Scoop buckets" -ForegroundColor Red
return @()
}
Write-Host "Found $($Apps.Count) apps with GitHub URLs" -ForegroundColor Gray
$Picked = @($Apps | Get-Random -Count ([Math]::Min($TargetCount, $Apps.Count)))
return $Picked | Sort-Object name
}
# Generate app config file if missing or requested
if ($RegenerateApps -or -not (Test-Path $AppConfig)) {
Write-Host "Picking $Count random apps from Scoop main/extras buckets..." -ForegroundColor Yellow
$Generated = New-TestApps $Count
$GenCount = @($Generated).Count
if ($GenCount -gt 0) {
$Generated | ConvertTo-Json -Depth 2 | Set-Content $AppConfig
Write-Host "Wrote $GenCount apps to $AppConfig" -ForegroundColor Green
} else {
Write-Host "ERROR: Could not generate app list" -ForegroundColor Red
exit 1
}
}
$ManifestDir = Join-Path $RepoDir "bucket"
if (-not (Test-Path $AppConfig)) {
Write-Host "ERROR: App config not found: $AppConfig" -ForegroundColor Red
exit 1
}
$Loaded = Get-Content $AppConfig -Raw | ConvertFrom-Json
$Apps = if ($Loaded -is [array]) { $Loaded } else { @($Loaded) }
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
# Load existing results to support resume
$Results = @()
if (Test-Path $ResultsFile) {
try {
$Loaded = Get-Content $ResultsFile -Raw | ConvertFrom-Json
# Ensure it's always a list (ConvertFrom-Json may return single PSObject)
$Results = if ($Loaded -is [array]) { $Loaded } else { @($Loaded) }
} catch { $Results = @() }
}
# Detect unchanged: app+url combo already tested
$ExistingKeys = $Results | ForEach-Object { "$($_.app)|$($_.url)" }
$Total = $Apps.Count
$Idx = 0
$TotalTime = 0
# Ctrl+C handler: set flag, DON'T cancel 鈥?let PowerShell handle the signal
$global:CtrlCPressed = $false
$null = [Console]::add_CancelKeyPress({
$global:CtrlCPressed = $true
Write-Host "`n[Ctrl+C] Interrupted. Saving current results then stopping..." -ForegroundColor Yellow
})
try {
foreach ($App in $Apps) {
$Idx++
$Name = $App.name
$Url = $App.url
$Key = "$Name|$Url"
$Time = Get-Date -Format "HH:mm:ss"
# Check Ctrl+C before starting each app
if ($global:CtrlCPressed) { Write-Host "[Ctrl+C] Stopping." -ForegroundColor Yellow; break }
if ($Key -in $ExistingKeys) {
Write-Host "[$Time] [$Idx/$Total] $Name : already tested, skip" -ForegroundColor Gray
continue
}
Write-Host "`n$('='*60)" -ForegroundColor Cyan
Write-Host "[$Time] [$Idx/$Total] Testing: $Name ($Url)" -ForegroundColor Yellow
$Result = @{
app = $Name
url = $Url
timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
generation_status = "SKIPPED"
install_status = "-"
}
# Record bucket files before generation to detect new files later
$BeforeFiles = if (Test-Path $ManifestDir) {
Get-ChildItem $ManifestDir -Filter *.json | Select-Object -ExpandProperty Name
} else { @() }
# Also try the expected name and fuzzy match
$ManifestPath = Join-Path $ManifestDir "$Name.json"
if (-not (Test-Path $ManifestPath)) {
$found = Get-ChildItem $ManifestDir -Filter "$Name*.json" -ErrorAction SilentlyContinue | 
Select-Object -First 1
if ($found) {
$ManifestPath = $found.FullName
} else {
$ManifestPath = $null
}
}
$InstalledAppName = if ($ManifestPath) { 
[System.IO.Path]::GetFileNameWithoutExtension($ManifestPath) } else { $Name }
$Result.installed_app_name = $InstalledAppName
if ($ManifestPath -and (Test-Path $ManifestPath)) { Remove-Item $ManifestPath -Force }
$LogStdout = Join-Path $LogDir "$Name.stdout.log"
$LogStderr = Join-Path $LogDir "$Name.stderr.log"
$GenStart = Get-Date
# ================================================================
# Step 1: Generate manifest
# ================================================================
Write-Host "  Generating..." -ForegroundColor Gray
try {
$AllArgs = @("-jar", $JarPath) + $CliBaseArgs + $Url
# Non-interactive mode: return default when LLM calls __ask_user__
$env:SCOOP_AI_NON_INTERACTIVE = "true"
$Proc = Start-Process -FilePath $Cli -ArgumentList $AllArgs -NoNewWindow -PassThru ` -RedirectStandardOutput $LogStdout -RedirectStandardError $LogStderr -Wait
$GenSec = [math]::Round(((Get-Date) - $GenStart).TotalSeconds, 1)
$Result.generation_time = $GenSec
# Check if interrupted by user (child process killed by Ctrl+C)
if ($global:CtrlCPressed) {
$Result.generation_status = "INTERRUPTED"
$Result.error = "user interrupted"
Write-Host "  [Ctrl+C] Generation interrupted." -ForegroundColor Yellow
break
} else {
if (Test-Path $LogStdout) { $Stdout = Get-Content $LogStdout -Raw } else { $Stdout = 
"" }
if (Test-Path $LogStderr) { $Stderr = Get-Content $LogStderr -Raw } else { $Stderr = 
"" }
$Combined = $Stdout + "`n" + $Stderr
if ($Combined -match "Agent completed\.") {
$Result.generation_status = "SUCCESS"
} elseif ($Combined -match "Subgraph with task must") {
$Result.generation_status = "FAILED"
$Result.error = "tool_not_found_crash"
} elseif ($Stderr -match "Error:" -or $Stderr -match "Exception in thread") {
$Err = ($Stderr -split "`n" | Where-Object { $_ -match "Error:|Exception" -and $_  -notmatch "WARN" } | Select-Object -First 1).Trim()
$Result.generation_status = "FAILED"
$Result.error = if ($Err.Length -gt 200) { $Err.Substring(0, 200) } else { $Err }
} else {
$Result.generation_status = "UNKNOWN"
$Result.error = "no completion marker"
}
}
} catch {
$Result.generation_status = "FAILED"
$ErrMsg = $_.Exception.Message
if ($ErrMsg.Length -gt 200) { $ErrMsg = $ErrMsg.Substring(0, 200) }
$Result.error = $ErrMsg
}
# ================================================================
# Step 2: Parse manifest
# ================================================================
# Resolve the actual generated manifest:
#   1. Extract "Written to ..." from stdout log (most reliable)
#   2. Diff bucket/ before/after file lists
#   3. Fallback to $Name* glob
if ($Result.generation_status -eq "SUCCESS") {
$ResolvedManifest = $null
# Method 1: Parse "Written to <path>" from stdout
if (Test-Path $LogStdout) {
$WrittenMatch = (Get-Content $LogStdout) | Select-String -Pattern 'Written to (.+)' | 
Select-Object -Last 1
if ($WrittenMatch) {
$WrittenPath = $WrittenMatch.Matches[0].Groups[1].Value.Trim()
# Resolve relative path
if (-not [System.IO.Path]::IsPathRooted($WrittenPath)) {
$WrittenPath = Join-Path $RepoDir $WrittenPath
}
if (Test-Path $WrittenPath) {
$ResolvedManifest = $WrittenPath
}
}
}
# Method 2: Diff bucket/ before/after
if (-not $ResolvedManifest -and (Test-Path $ManifestDir)) {
$AfterFiles = Get-ChildItem $ManifestDir -Filter *.json | Select-Object  -ExpandProperty Name
$NewFiles = $AfterFiles | Where-Object { $_ -notin $BeforeFiles }
if ($NewFiles.Count -eq 1) {
$ResolvedManifest = Join-Path $ManifestDir $NewFiles
} elseif ($NewFiles.Count -gt 1) {
# Multiple new files 鈥?pick the most recently written one
$ResolvedManifest = ($NewFiles | ForEach-Object {
$fp = Join-Path $ManifestDir $_
[PSCustomObject]@{Name = $fp; Time = (Get-Item $fp).LastWriteTime}
} | Sort-Object Time -Descending | Select-Object -First 1).Name
}
}
# Method 3: Fallback to fuzzy name match
if (-not $ResolvedManifest -and (Test-Path $ManifestDir)) {
$found = Get-ChildItem $ManifestDir -Filter "$Name*.json" | Select-Object -First 1
if ($found) {
$ResolvedManifest = $found.FullName
}
}
if ($ResolvedManifest) {
$ManifestPath = $ResolvedManifest
$InstalledAppName = [System.IO.Path]::GetFileNameWithoutExtension($ManifestPath)
$Result.installed_app_name = $InstalledAppName
}
}
if ($Result.generation_status -eq "SUCCESS" -and $ManifestPath -and (Test-Path  $ManifestPath)) {
try {
$Data = Get-Content $ManifestPath -Raw | ConvertFrom-Json
$MInfo = @{
has_version = $null -ne $Data.version
has_description = -not [string]::IsNullOrWhiteSpace($Data.description)
has_homepage = -not [string]::IsNullOrWhiteSpace($Data.homepage)
has_license = -not [string]::IsNullOrWhiteSpace($Data.license)
has_bin = $null -ne $Data.bin
has_checkver = $null -ne $Data.checkver
has_autoupdate = $null -ne $Data.autoupdate
has_persist = $null -ne $Data.persist
}
# Check URL for $version placeholder (bug)
$UrlVal = $null
if ($Data.url) { $UrlVal = $Data.url }
elseif ($Data.architecture) {
foreach ($a in @("64bit", "32bit", "arm64")) {
if ($Data.architecture.$a -and $Data.architecture.$a.url) {
$UrlVal = $Data.architecture.$a.url; break
}
}
}
if ($UrlVal) {
$MInfo.url_starts_http = $UrlVal.StartsWith("https://") -or 
$UrlVal.StartsWith("http://")
$MInfo.url_placeholder = $UrlVal -match '\$version'
}
# ================================================================
# Compare with official manifest from Scoop buckets
# ================================================================
$Official = Find-OfficialManifest $Name $InstalledAppName
$Compare = Compare-ManifestWithOfficial $Data $Official
$MInfo.official = $Compare.official_found
$MInfo.official_source = $Compare.official_source
$MInfo.diff = $Compare.diffs
$MInfo.diff_count = $Compare.diff_count
$Result.manifest = $MInfo
$Result.manifest_size = (Get-Item $ManifestPath).Length
} catch {
$Result.manifest = @{ error = $_.Exception.Message.Substring(0, [Math]::Min(100, 
$_.Exception.Message.Length)) }
}
} else {
$Result.manifest = $null
}
# ================================================================
# Step 3: Install test
# ================================================================
if ($Result.generation_status -eq "SUCCESS" -and $ManifestPath -and (Test-Path  $ManifestPath)) {
Write-Host "  Installing..." -ForegroundColor Gray
try {
$InstOut = & scoop install $ManifestPath 2>&1 | Out-String -Width 4096
# Detect install result from multiple patterns
if ($InstOut -match "Can't shim") {
$Result.install_status = "FAILED"
$Err = ($InstOut -split "`n" | Where-Object { $_ -match "Can't shim" } | 
Select-Object -First 1).Trim()
$Result.install_error = if ($Err.Length -gt 200) { $Err.Substring(0, 200) } else 
{ $Err }
} elseif ($InstOut -match "was installed successfully") {
$Result.install_status = "SUCCESS"
} elseif ($InstOut -match "already installed") {
$Result.install_status = "ALREADY_EXISTS"
} elseif ($InstOut -match "ERROR Exit code") {
$Result.install_status = "FAILED"
$Err = ($InstOut -split "`n" | Where-Object { $_ -match "ERROR" } | Select-Object  -First 1).Trim()
$Result.install_error = if ($Err.Length -gt 200) { $Err.Substring(0, 200) } else 
{ $Err }
} elseif ($InstOut -match "Extracting.*done" -or $InstOut -match "Creating shim for") 
{
# Scoop completed extract + shim without printing success message
$Result.install_status = "SUCCESS"
} else {
$Result.install_status = "UNKNOWN"
$Out = $InstOut.Replace("`n", " ").Replace("`r", "")
if ($Out.Length -gt 200) { $Out = $Out.Substring(0, 200) }
$Result.install_output = $Out
}
} catch {
$Result.install_status = "FAILED"
$ErrMsg = $_.Exception.Message
if ($ErrMsg.Length -gt 200) { $ErrMsg = $ErrMsg.Substring(0, 200) }
$Result.install_error = $ErrMsg
}
}
# ================================================================
# Save
# ================================================================
$Results += $Result
$Results | ConvertTo-Json -Depth 5 | Set-Content $ResultsFile
$Gen = $Result.generation_status
$Inst = $Result.install_status
$GenSec = $Result.generation_time
$M = $Result.manifest
if ($M -and $M.has_checkver -ne $null) {
$Cv = $M.has_checkver; $Au = $M.has_autoupdate; $Bi = $M.has_bin; $Pe = $M.has_persist
$Dif = if ($M.diff_count -gt 0) { $M.diff_count } else { 0 }
$TimeStr = if ($GenSec) { "time=${GenSec}s" } else { "" }
$Line = "  gen=$Gen install=$Inst $($TimeStr.PadRight(10))checkver=$Cv autoupdate=$Au 
bin=$Bi persist=$Pe"
if ($M.official) {
$Line += " off=$($M.official_source) diff=$Dif"
} else {
$Line += " off=-"
}
Write-Host $Line -ForegroundColor $(if ($Gen -eq "SUCCESS") { "Green" } else { "Red" })
# Show top 3 diffs inline if any
if ($M.diff_count -gt 0 -and $M.diff) {
$M.diff.Keys | Select-Object -First 3 | ForEach-Object {
$d = $M.diff[$_]
Write-Host "    ${$_}: $($d.status)" -ForegroundColor DarkYellow
}
if ($M.diff_count -gt 3) {
Write-Host "    ... and $($M.diff_count - 3) more diffs" -ForegroundColor DarkYellow
}
}
}
if ($Result.error) { Write-Host "  Error: $($Result.error)" -ForegroundColor Red }
if ($Result.install_error) { Write-Host "  Install Error: $($Result.install_error)"  -ForegroundColor Red }
}
} finally {
$Results | ConvertTo-Json -Depth 5 | Set-Content $ResultsFile -ErrorAction SilentlyContinue
Write-Host "Results saved to $ResultsFile" -ForegroundColor Yellow
}
# ================================================================
# Cleanup: uninstall all successfully installed apps
# ================================================================
Write-Host "`n$('='*60)" -ForegroundColor Cyan
Write-Host "Cleaning up..." -ForegroundColor Yellow
$Results | Where-Object { $_.install_status -eq "SUCCESS" } | ForEach-Object {
$appName = if ($_.installed_app_name) { $_.installed_app_name } else { $_.app }
Write-Host "  Uninstalling $appName..." -ForegroundColor Gray; & scoop uninstall $appName 2>&1 | Out-Null
}
Write-Host "Done." -ForegroundColor Green
# ================================================================
# Final report
# ================================================================
Write-Host "`n$('='*60)" -ForegroundColor Cyan
Write-Host "TEST COMPLETE" -ForegroundColor Green
Write-Host "$('='*60)" -ForegroundColor Cyan
$Succ = ($Results | Where-Object { $_.generation_status -eq "SUCCESS" }).Count
$Fail = ($Results | Where-Object { $_.generation_status -eq "FAILED" }).Count
$InstOk = ($Results | Where-Object { $_.install_status -eq "SUCCESS" }).Count
$InstFail = ($Results | Where-Object { $_.install_status -eq "FAILED" }).Count
Write-Host "Summary: $Total apps | gen: $Succ ok, $Fail failed | install: $InstOk ok, $InstFail 
failed" -ForegroundColor Yellow
# Per-app table
$Results | ForEach-Object {
$G = $_.generation_status
$I = $_.install_status
$M = $_.manifest
$Cv = if ($M -and $M.has_checkver -ne $null) { $M.has_checkver } else { "?" }
$Au = if ($M -and $M.has_autoupdate -ne $null) { $M.has_autoupdate } else { "?" }
$Pe = if ($M -and $M.has_persist -ne $null) { $M.has_persist } else { "?" }
$Off = if ($M -and $M.official) { $M.official_source } else { "-" }
$Diff = if ($M -and $M.diff_count -gt 0) { "$($M.diff_count)d" } else { "0d" }
$Err = $_.error; if (-not $Err) { $Err = $_.install_error }
if ($Err -and $Err.Length -gt 50) { $Err = $Err.Substring(0, 50) + "..." }
$TimeStr = if ($_.generation_time) { "time=$($_.generation_time)s" } else { "" }
Write-Host "  $($_.app.PadRight(20)) gen=$($G.PadRight(8)) 
$($TimeStr.PadRight(12))inst=$($I.PadRight(18)) cv=$Cv au=$Au pers=$Pe off=$Off diff=$Diff $Err"
# Show diff details if any
if ($M -and $M.diff -and ($M.diff.Keys | Measure-Object).Count -gt 0) {
$M.diff.Keys | ForEach-Object {
$d = $M.diff[$_]
Write-Host "    ${$_}: $($d.status)" -ForegroundColor DarkYellow
}
}
}
Write-Host "`nResults: $ResultsFile" -ForegroundColor Cyan
Write-Host "Logs: $LogDir" -ForegroundColor Cyan
