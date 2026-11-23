$ErrorActionPreference = 'Stop'
& python (Join-Path $PSScriptRoot 'frontend\manage.py') check
exit $LASTEXITCODE
