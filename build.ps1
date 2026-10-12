param(
    [string]$IdeaHome = $env:IDEA_HOME,
    [string]$JdkHome
)
$ErrorActionPreference = 'Stop'
if (-not $IdeaHome) { throw 'Set IDEA_HOME or pass -IdeaHome <IntelliJ installation directory>.' }
$IdeaHome = (Resolve-Path -LiteralPath $IdeaHome).Path
if (-not $JdkHome -and (Test-Path -LiteralPath "$IdeaHome/jbr/bin/javac.exe")) { $JdkHome = "$IdeaHome/jbr" }
if (-not $JdkHome) { $JdkHome = $env:JAVA_HOME }
if (-not $JdkHome) { throw 'Set JAVA_HOME or pass -JdkHome <JDK 21+ directory>.' }
$JdkHome = (Resolve-Path -LiteralPath $JdkHome).Path
$projectPath = $PSScriptRoot
[xml]$manifest = Get-Content -LiteralPath (Join-Path $projectPath 'src/main/resources/META-INF/plugin.xml') -Raw
$pluginVersion = [string]$manifest.'idea-plugin'.version
$minimumBuild = [string]$manifest.'idea-plugin'.'idea-version'.'since-build'
$sdkBuild = (Get-Content -LiteralPath (Join-Path $IdeaHome 'build.txt') -Raw).Trim() -replace '^[A-Z]+-', ''
if ($sdkBuild.Split('.')[0] -ne $minimumBuild.Split('.')[0]) {
    throw "Use an IntelliJ $($minimumBuild.Split('.')[0]) SDK for this plugin baseline; selected SDK is $sdkBuild."
}
$buildPath = Join-Path $projectPath 'build'
$classesPath = Join-Path $buildPath "manual-$pluginVersion/classes"
$testPath = Join-Path $buildPath "manual-$pluginVersion/test-classes"
$stageRoot = Join-Path $buildPath "package-$pluginVersion"
$stagePath = Join-Path $stageRoot 'mollm-intellij/lib'
$distributionPath = Join-Path $buildPath 'distributions'
New-Item -ItemType Directory -Force -Path $classesPath,$testPath,$stagePath,$distributionPath | Out-Null
$compilerPath = Join-Path $JdkHome 'bin/javac.exe'
$runtimePath = Join-Path $JdkHome 'bin/java.exe'
if (-not (Test-Path -LiteralPath $compilerPath)) { throw "javac not found: $compilerPath" }
$mainSources = Get-ChildItem (Join-Path $projectPath 'src/main/java') -Recurse -Filter *.java | ForEach-Object FullName
$argumentsPath = Join-Path $buildPath 'javac.args'
$sdkClasspath = (Get-ChildItem -LiteralPath "$IdeaHome/lib" -Filter *.jar | ForEach-Object { $_.FullName.Replace('\','/') }) -join ';'
$compilerArgs = @('--release', '21', '-encoding', 'UTF-8', '-classpath', ('"' + $sdkClasspath + '"'), '-d', ('"' + $classesPath.Replace('\','/') + '"'))
$compilerArgs += $mainSources | ForEach-Object { '"' + $_.Replace('\','/') + '"' }
[IO.File]::WriteAllLines($argumentsPath, $compilerArgs, [Text.UTF8Encoding]::new($false))
& $compilerPath "@$argumentsPath"
if ($LASTEXITCODE -ne 0) { throw 'Main compilation failed.' }
$resourcesPath = Join-Path $projectPath 'src/main/resources'
foreach ($resource in Get-ChildItem -LiteralPath $resourcesPath -Recurse -File) {
    $relativeResource = [IO.Path]::GetRelativePath($resourcesPath, $resource.FullName)
    $targetResource = Join-Path $classesPath $relativeResource
    New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($targetResource)) | Out-Null
    Copy-Item -LiteralPath $resource.FullName -Destination $targetResource -Force
}
$testSources = Get-ChildItem (Join-Path $projectPath 'src/test/java') -Recurse -Filter *.java | ForEach-Object FullName
& $compilerPath --release 21 --add-modules jdk.httpserver -encoding UTF-8 -classpath "$classesPath;$IdeaHome/lib/*" -d $testPath $testSources
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
& $runtimePath --add-modules jdk.httpserver '-Djava.awt.headless=true' -classpath "$testPath;$classesPath;$IdeaHome/lib/*" tw.com.fmt.mollm.GatewayTest
if ($LASTEXITCODE -ne 0) { throw 'Gateway tests failed.' }
Copy-Item (Join-Path $projectPath 'LICENSE.txt') $classesPath -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jarPath = Join-Path $stagePath "mollm-intellij-$pluginVersion.jar"
if (Test-Path -LiteralPath $jarPath) { Remove-Item -LiteralPath $jarPath }
[IO.Compression.ZipFile]::CreateFromDirectory($classesPath, $jarPath)
$zipPath = Join-Path $distributionPath "mollm-intellij-$pluginVersion.zip"
if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath }
[IO.Compression.ZipFile]::CreateFromDirectory($stageRoot, $zipPath)
Write-Output "Plugin built: $zipPath"
