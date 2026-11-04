<#
.SYNOPSIS
    Создаёт на рабочем столе ярлык "Фитнес", который запускает fitapp без консоли.

.DESCRIPTION
    Ярлык указывает на wscript.exe, а launch_hidden.vbs передаётся аргументом.
    Так надёжнее, чем ярлык на сам .vbs: работает даже если ассоциация
    файлов .vbs в системе сбита или отключена.

    Иконка берётся у java.exe из JDK (логотип Java). Если JDK не найден,
    используется стандартная иконка Windows из shell32.dll.

.PARAMETER ShortcutName
    Имя ярлыка без расширения .lnk. По умолчанию "Фитнес".

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File .\create_shortcut.ps1

.EXAMPLE
    # проверить, что скрипт найдёт все пути, но НЕ создавать ярлык
    .\create_shortcut.ps1 -WhatIf
#>

[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$ShortcutName = 'Фитнес'
)

# Любая ошибка останавливает скрипт, а не проглатывается.
$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------- 1. пути

# Папка этого скрипта = корень проекта fitapp.
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

$vbs = Join-Path $scriptDir 'launch_hidden.vbs'
$bat = Join-Path $scriptDir 'run.bat'

foreach ($required in @($vbs, $bat)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "Не найден файл: $required. Скрипт должен лежать в корне проекта fitapp."
    }
}

$wscript = Join-Path $env:SystemRoot 'System32\wscript.exe'

# ------------------------------------------------------------- 2. иконка

# Запасной вариант - стандартная иконка Windows.
$icon = "$env:SystemRoot\System32\shell32.dll,13"

# Ищем java.exe от JDK 17/21: у него оранжевый логотип Java.
$javaPatterns = @(
    "$env:ProgramFiles\Eclipse Adoptium\jdk-17*\bin\java.exe",
    "$env:ProgramFiles\Eclipse Adoptium\jdk-21*\bin\java.exe",
    "$env:ProgramFiles\Java\jdk-17*\bin\java.exe",
    "$env:ProgramFiles\Java\jdk-21*\bin\java.exe",
    "$env:USERPROFILE\.jdks\openjdk-17*\bin\java.exe"
)

foreach ($pattern in $javaPatterns) {
    $found = Get-ChildItem -Path $pattern -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($found) {
        $icon = "$($found.FullName),0"
        break
    }
}

# ------------------------------------------------------- 3. рабочий стол

$desktop = [Environment]::GetFolderPath('Desktop')
$linkPath = Join-Path $desktop "$ShortcutName.lnk"

# -------------------------------------------------------- 4. создание

if ($PSCmdlet.ShouldProcess($linkPath, 'Создать ярлык')) {

    # Старый ярлык с тем же именем заменяем: иначе Windows оставит прежнюю иконку.
    if (Test-Path -LiteralPath $linkPath) {
        Remove-Item -LiteralPath $linkPath -Force
    }

    $shell = New-Object -ComObject WScript.Shell
    try {
        $shortcut = $shell.CreateShortcut($linkPath)

        $shortcut.TargetPath = $wscript
        $shortcut.Arguments = '"' + $vbs + '"'
        $shortcut.WorkingDirectory = $scriptDir
        $shortcut.Description = 'fitapp - домашние тренировки'
        $shortcut.IconLocation = $icon
        $shortcut.WindowStyle = 1

        $shortcut.Save()
    }
    finally {
        # COM-объекты освобождаем, иначе файл ярлыка иногда остаётся занятым.
        if ($shortcut) { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($shortcut) }
        [void][Runtime.InteropServices.Marshal]::ReleaseComObject($shell)
    }

    # Сброс кэша иконок: без этого Windows может показать старую картинку.
    try { & "$env:SystemRoot\System32\ie4uinit.exe" -show } catch { }
}

# ------------------------------------------------------------- 5. отчёт

Write-Host ''
Write-Host 'Готово: ярлык создан' -ForegroundColor Green
Write-Host "  ярлык:  $linkPath"
Write-Host "  цель:   $wscript"
Write-Host "  скрипт: $vbs"
Write-Host "  иконка: $icon"
Write-Host "  лог:    $(Join-Path $scriptDir 'launch.log')"
Write-Host ''
Write-Host 'Первый запуск занимает 5-10 секунд: Maven поднимает JavaFX без окна консоли.'
