@echo off
set "DIR=%~dp0"
set "JAVA21=C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot\bin\java.exe"
if exist "%JAVA21%" (
    "%JAVA21%" -jar "%DIR%dara.jar"
) else (
    java -jar "%DIR%dara.jar"
)
