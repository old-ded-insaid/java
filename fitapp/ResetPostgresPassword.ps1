<#
.SYNOPSIS
    Сбрасывает пароль роли postgres на "postgres" (локально, для домашнего проекта).

.DESCRIPTION
    Зачем: приложение ожидает пароль postgres, но сервер его не принимает,
    а текущий пароль неизвестен. Восстановление идёт стандартным способом:

      1. делаем резервную копию pg_hba.conf
      2. временно разрешаем локальный вход без пароля (trust)
      3. перезапускаем службу PostgreSQL
      4. выполняем ALTER ROLE postgres WITH PASSWORD 'postgres'
      5. ВСЕГДА возвращаем pg_hba.conf обратно и перезапускаем службу
      6. проверяем, что вход по новому паролю работает

    ВАЖНО:
      - Нужны права администратора (меняется файл в Program Files и служба).
      - Пока действует trust (несколько секунд), любой локальный пользователь
        может войти в БД без пароля. Скрипт сразу возвращает настройки.

.PARAMETER PostgresHome
    Папка установки PostgreSQL. По умолчанию C:\Program Files\PostgreSQL\18

.PARAMETER Role
    Роль, которой меняем пароль. По умолчанию postgres.

.PARAMETER NewPassword
    Новый пароль. По умолчанию postgres (его и ждёт application.properties).

.EXAMPLE
    # запускать в PowerShell, открытом "от имени администратора"
    powershell -ExecutionPolicy Bypass -File .\ResetPostgresPassword.ps1
#>

[CmdletBinding()]
param(
    [string]$PostgresHome = 'C:\Program Files\PostgreSQL\18',
    [string]$Role = 'postgres',
    [string]$NewPassword = 'postgres'
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------- 0. проверки

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $isAdmin) {
    throw @'
Нужны права администратора.
Закройте это окно и запустите PowerShell через
"Запуск от имени администратора", затем повторите команду.
'@
}

$hba = Join-Path $PostgresHome 'data\pg_hba.conf'
$psql = Join-Path $PostgresHome 'bin\psql.exe'
$service = Get-Service -Name 'postgresql*' -ErrorAction SilentlyContinue | Select-Object -First 1

foreach ($path in @($hba, $psql)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Не найден файл: $path. Укажите папку установки: -PostgresHome 'C:\Program Files\PostgreSQL\18'"
    }
}
if (-not $service) {
    throw 'Служба postgresql* не найдена. PostgreSQL установлен?'
}

$backup = "$hba.bak-" + (Get-Date -Format 'yyyyMMdd-HHmmss')
Copy-Item -LiteralPath $hba -Destination $backup -Force
Write-Host "Резервная копия pg_hba.conf: $backup"
Write-Host "Служба: $($service.Name) (сейчас: $($service.Status))"

# psql с повторами: служба после перезапуска поднимается не мгновенно
function Invoke-Psql([string]$sql, [switch]$Scalar) {
    $lastError = $null
    for ($i = 1; $i -le 15; $i++) {
        $psqlArgs = @('-U', $Role, '-h', '127.0.0.1', '-p', '5432', '-v', 'ON_ERROR_STOP=1')
        if ($Scalar) { $psqlArgs += '-tAc' } else { $psqlArgs += '-c' }
        $psqlArgs += $sql

        $output = & $psql @psqlArgs 2>&1
        if ($LASTEXITCODE -eq 0) { return $output }

        $lastError = $output
        Start-Sleep -Seconds 1
    }
    throw "psql не смог выполнить запрос: $lastError"
}

try {
    # -------------------------------------------------- 1. временный trust

    $text = [IO.File]::ReadAllText($hba)

    # только локальные подключения, репликацию не трогаем
    $trusted = [regex]::Replace($text,
        '(?m)^(local\s+all\s+all\s+)(?:scram-sha-256|md5)', '${1}trust')
    $trusted = [regex]::Replace($trusted,
        '(?m)^(host\s+all\s+all\s+(?:127\.0\.0\.1/32|::1/128)\s+)(?:scram-sha-256|md5)', '${1}trust')

    if ($trusted -eq $text) {
        Write-Warning 'В pg_hba.conf не нашлось строк scram-sha-256 для localhost - файл не изменён.'
    } else {
        [IO.File]::WriteAllText($hba, $trusted, (New-Object System.Text.UTF8Encoding($false)))
        Write-Host 'pg_hba.conf: локальный вход временно переведён в trust.'
    }

    Restart-Service -Name $service.Name -Force
    Write-Host 'Служба перезапущена, ждём готовности...'

    # --------------------------------------- 2. смена пароля (без пароля)

    Remove-Item Env:\PGPASSWORD -ErrorAction SilentlyContinue
    Invoke-Psql "ALTER ROLE $Role WITH PASSWORD '$NewPassword';" | Out-Null
    Write-Host "Пароль роли '$Role' изменён." -ForegroundColor Green
}
finally {
    # ------------------------------------ 3. всегда возвращаем scram-sha-256

    Copy-Item -LiteralPath $backup -Destination $hba -Force
    Restart-Service -Name $service.Name -Force
    Remove-Item Env:\PGPASSWORD -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
    Write-Host 'pg_hba.conf возвращён к исходным настройкам, служба перезапущена.'
}

# ------------------------------------------------------- 4. проверка входа

$env:PGPASSWORD = $NewPassword
try {
    $check = Invoke-Psql "select current_user || ' - вход по паролю работает';" -Scalar
    Write-Host "Проверка: $($check -join ' ')" -ForegroundColor Green
}
finally {
    Remove-Item Env:\PGPASSWORD -ErrorAction SilentlyContinue
}

Write-Host ''
Write-Host 'Готово. Теперь можно запускать приложение:' -ForegroundColor Green
Write-Host '  .\run.bat'
