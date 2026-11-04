@echo off
rem ===========================================================================
rem  run.bat - запуск fitapp (JavaFX + Maven)
rem
rem  Файл лежит в КОРНЕ проекта fitapp, рядом с pom.xml.
rem
rem  Обычный запуск:          run.bat        (сборка + запуск)
rem  Быстрый запуск:          run.bat fast   (без mvn install)
rem
rem  Скрипт сам ищет Java и Maven:
rem    1. в PATH
rem    2. в JAVA_HOME / MAVEN_HOME
rem    3. в папках ~/.jdks, Program Files (JDK 17 и 21 приоритетнее)
rem    4. в Maven, встроенном в IntelliJ IDEA
rem  Поэтому предварительно прописывать PATH не обязательно.
rem ===========================================================================

rem 1. UTF-8, чтобы русские буквы в консоли читались нормально.
rem    Файл сохранён в UTF-8 БЕЗ BOM (BOM ломает первую строку cmd).
chcp 65001 >nul
setlocal EnableExtensions

rem 2. Работаем в папке этого файла, а не там, откуда кликнули.
cd /d "%~dp0"

title fitapp
echo Запуск fitapp...
echo.

rem ============================ 1. ПОИСК JAVA ================================
set "JAVA_EXE="

rem 1.1 Сначала PATH - вдруг java уже настроена.
for /f "delims=" %%I in ('where java 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%I"

rem 1.2 JAVA_HOME - стандартное место.
if not defined JAVA_EXE if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"

rem 1.3 JDK, скачанные IntelliJ IDEA (~/.jdks), и ручные установки.
rem    17 и 21 идут первыми: с ними проект гарантированно собирается.
if not defined JAVA_EXE call :findJdk "%USERPROFILE%\.jdks\openjdk-17*"
if not defined JAVA_EXE call :findJdk "%USERPROFILE%\.jdks\openjdk-21*"
if not defined JAVA_EXE call :findJdk "C:\Program Files\Eclipse Adoptium\jdk-17*"
if not defined JAVA_EXE call :findJdk "C:\Program Files\Eclipse Adoptium\jdk-21*"
if not defined JAVA_EXE call :findJdk "C:\Program Files\Java\jdk-17*"
if not defined JAVA_EXE call :findJdk "C:\Program Files\Java\jdk-21*"
if not defined JAVA_EXE call :findJdk "%USERPROFILE%\.jdks\*"
if not defined JAVA_EXE call :findJdk "C:\Program Files\Java\*"
if not defined JAVA_EXE call :findJbrIntellij

if not defined JAVA_EXE (
    echo [ОШИБКА] Java не найдена.
    echo.
    echo Установите JDK 17 одной командой ^(нужен интернет^):
    echo     winget install --id EclipseAdoptium.Temurin.17.JDK -e
    echo После установки закройте это окно и запустите run.bat снова.
    goto :fail
)

rem JAVA_HOME = папка JDK, то есть на уровень выше bin.
for %%I in ("%JAVA_EXE%") do set "JAVA_BIN=%%~dpI"
for %%I in ("%JAVA_BIN%..") do set "JAVA_HOME=%%~fI"
set "PATH=%JAVA_HOME%\bin;%PATH%"

rem Версия Java нужна только для предупреждения.
rem Тонкость: внутри for /f ('...') команда с кавычками и пробелом в пути
rem ("C:\Program Files\...") ломается - cmd обрезает первую и последнюю кавычку.
rem Поэтому сначала пишем вывод java в файл, потом читаем файл.
set "JAVA_VERSION="
set "JAVA_MAJOR="
set "JAVA_VERSION_FILE=%TEMP%\fitapp_java_version.txt"
"%JAVA_EXE%" -version > "%JAVA_VERSION_FILE%" 2>&1
for /f "tokens=3" %%V in ('findstr /i "version" "%JAVA_VERSION_FILE%"') do set "JAVA_VERSION=%%~V"
for /f "tokens=1 delims=." %%M in ("%JAVA_VERSION%") do set "JAVA_MAJOR=%%M"
del "%JAVA_VERSION_FILE%" >nul 2>nul

echo Java:  %JAVA_EXE%
if defined JAVA_VERSION (
    echo Версия: %JAVA_VERSION%
) else (
    echo Версия: не определилась
)

rem Проверка безопасна: пустое значение отсекаем ДО сравнения,
rem иначе получается "if GEQ 23" и cmd ругается "23 was unexpected".
if not defined JAVA_MAJOR goto :java_check_done
if %JAVA_MAJOR% LSS 23 goto :java_check_done
echo [ВНИМАНИЕ] Найдена Java %JAVA_MAJOR%. Проект рассчитан на JDK 17-21.
echo Сборка на ней проходит, но JavaFX 21 официально поддерживает JDK 17-21.
echo Если появятся странности в интерфейсе - поставьте JDK 17.
echo.
:java_check_done

rem ============================ 2. ПОИСК MAVEN ===============================
set "MVN_CMD="

rem 2.1 PATH.
for /f "delims=" %%I in ('where mvn 2^>nul') do if not defined MVN_CMD set "MVN_CMD=%%I"

rem 2.2 MAVEN_HOME.
if not defined MVN_CMD if defined MAVEN_HOME if exist "%MAVEN_HOME%\bin\mvn.cmd" set "MVN_CMD=%MAVEN_HOME%\bin\mvn.cmd"

rem 2.3 Обычные места установки + Maven, встроенный в IntelliJ IDEA.
if not defined MVN_CMD call :findMvn "C:\Program Files\Apache\maven*"
if not defined MVN_CMD call :findMvn "C:\Tools\apache-maven*"
if not defined MVN_CMD call :findMvn "%USERPROFILE%\apache-maven*"
if not defined MVN_CMD call :findMvnIntellij

if not defined MVN_CMD (
    echo [ОШИБКА] Maven не найден.
    echo.
    echo Вариант 1: скачайте apache-maven-3.9.x-bin.zip с maven.apache.org
    echo             и распакуйте в C:\Tools\apache-maven-3.9.9
    echo Вариант 2: запускайте сборку из IntelliJ IDEA ^(там Maven встроен^).
    goto :fail
)

echo Maven: %MVN_CMD%
echo.

rem Русские сообщения Maven в консоли UTF-8.
set "MAVEN_OPTS=%MAVEN_OPTS% -Dfile.encoding=UTF-8"

rem ====================== 3. ПРОВЕРКА POSTGRESQL =============================
netstat -ano | findstr /C:":5432" | findstr /C:"LISTENING" >nul
if errorlevel 1 (
    echo [ВНИМАНИЕ] PostgreSQL на порту 5432 не отвечает.
    echo Приложение запустится, но подключение к базе может не пройти.
    echo Проверьте службу: services.msc -^> postgresql-x64-18
    echo.
)

rem ==================== 4. СБОРКА ПЕРЕД ЗАПУСКОМ ==========================
rem Тонкость: javafx:run собирает только модуль desktop, а core должен
rem уже лежать в локальном репозитории ~/.m2. Иначе Maven скажет:
rem   Could not find artifact com.fitapp:core:jar:0.1.0-SNAPSHOT
rem Поэтому по умолчанию делаем mvn install, а режим "fast" его пропускает.
if /i "%~1"=="fast" (
    echo Быстрый запуск без сборки ^(run.bat fast^).
    echo.
    goto :runApp
)

echo Сборка проекта ^(mvn install^)...
call "%MVN_CMD%" -q install -DskipTests
if errorlevel 1 (
    echo.
    echo [ОШИБКА] Сборка не удалась. Смотрите сообщения Maven выше.
    goto :fail
)
echo Сборка завершена.
echo.

:runApp

rem ============================== 5. ЗАПУСК =================================
call "%MVN_CMD%" -pl desktop javafx:run
set "EXIT_CODE=%errorlevel%"

echo.
if not "%EXIT_CODE%"=="0" (
    echo [ОШИБКА] Приложение завершилось с кодом %EXIT_CODE%.
    echo.
    echo Частые причины:
    echo   - неверный пароль в core\src\main\resources\application.properties
    echo   - база fitapp ещё не создана: createdb -U postgres fitapp
    echo   - служба PostgreSQL не запущена: services.msc
    echo.
    echo Подробности - в первых строках со словом ERROR выше.
    goto :fail
)

echo fitapp закрыт.
exit /b 0

rem ============================== СЛУЖЕБНОЕ =================================
:fail
echo.
echo Нажмите любую клавишу, чтобы закрыть окно...
pause >nul
exit /b 1

rem %~1 - маска папки, внутри которой лежит bin\java.exe
:findJdk
for /d %%D in ("%~1") do (
    if not defined JAVA_EXE if exist "%%~fD\bin\java.exe" set "JAVA_EXE=%%~fD\bin\java.exe"
)
exit /b 0

rem %~1 - маска папки, внутри которой лежит bin\mvn.cmd
rem Внимание: for /d не умеет звёздочку в СЕРЕДИНЕ пути,
rem поэтому IntelliJ ищем отдельным перебором (см. :findMvnIntellij).
:findMvn
for /d %%D in ("%~1") do (
    if not defined MVN_CMD if exist "%%~fD\bin\mvn.cmd" set "MVN_CMD=%%~fD\bin\mvn.cmd"
)
exit /b 0

rem Maven, встроенный в IntelliJ IDEA: C:\Program Files\JetBrains\<IDE>\plugins\maven-plugin\lib\maven3
:findMvnIntellij
for /d %%J in ("C:\Program Files\JetBrains\*") do (
    if not defined MVN_CMD if exist "%%~fJ\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" set "MVN_CMD=%%~fJ\plugins\maven-plugin\lib\maven3\bin\mvn.cmd"
    if not defined MVN_CMD if exist "%%~fJ\plugins\maven\lib\maven3\bin\mvn.cmd" set "MVN_CMD=%%~fJ\plugins\maven\lib\maven3\bin\mvn.cmd"
)
exit /b 0

rem Java из IntelliJ (JBR) - самый последний вариант, если JDK нет вообще.
:findJbrIntellij
for /d %%J in ("C:\Program Files\JetBrains\*") do (
    if not defined JAVA_EXE if exist "%%~fJ\jbr\bin\java.exe" set "JAVA_EXE=%%~fJ\jbr\bin\java.exe"
)
exit /b 0
