$env:JAVA_HOME = "C:\Users\yorri\.jdks\loom-ea-25-loom+1-11"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Write-Host "Java 25 is now active" -ForegroundColor Green
java -version