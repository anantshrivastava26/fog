# Build and run the simulator on Windows.
#   .\run.ps1                  -> full pipeline (sensor data, RL training, policy comparison, fault study)
#   .\run.ps1 quick            -> 2-hour smoke test
#   .\run.ps1 eval --eval-days 1
# Requires JDK 21+ (iFogSim 2 uses Java 21 language features).
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$SimArgs)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

function Find-Jdk {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    foreach ($root in 'C:\Program Files\Java', 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Microsoft', "$env:USERPROFILE\.jdks") {
        if (Test-Path $root) { $candidates += (Get-ChildItem $root -Directory | Sort-Object Name -Descending).FullName }
    }
    foreach ($c in $candidates) {
        $javac = Join-Path $c 'bin\javac.exe'
        if (-not (Test-Path $javac)) { continue }
        $v = & $javac -version 2>&1 | Out-String
        if ($v -match 'javac (\d+)' -and [int]$Matches[1] -ge 21) { return $c }
    }
    throw 'JDK 21 or newer not found. Install one and/or set JAVA_HOME.'
}

$jdk = Find-Jdk
$cp = ((Get-ChildItem lib -Filter *.jar).FullName) -join ';'
New-Item -ItemType Directory -Force build\classes | Out-Null
$sources = Get-ChildItem src\main\java -Recurse -Filter *.java | ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' }
$sources | Set-Content build\sources.txt
& "$jdk\bin\javac.exe" -encoding UTF-8 -d build\classes -cp $cp '@build\sources.txt'
if ($LASTEXITCODE -ne 0) { throw 'compilation failed' }
& "$jdk\bin\java.exe" -Xmx2g -cp "build\classes;$cp" carbonfog.Main @SimArgs
