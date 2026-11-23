$ErrorActionPreference = 'Stop'
& python (Join-Path $PSScriptRoot 'frontend\manage.py') start
exit $LASTEXITCODE
