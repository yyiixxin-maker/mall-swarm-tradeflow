$ErrorActionPreference = 'Stop'
& python (Join-Path $PSScriptRoot 'frontend\manage.py') stop
exit $LASTEXITCODE
