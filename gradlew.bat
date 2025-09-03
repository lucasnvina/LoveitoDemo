@ECHO OFF
SET DIR=%~dp0
IF EXIST "%DIR%\gradlew.bat" (
  "%DIR%\gradlew.bat" %*
) ELSE (
  ECHO Gradle wrapper script not found. Use 'gradlew.bat' from project root.
)
