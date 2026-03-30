@echo off
setlocal

if exist "C:\Program Files\Java\jdk-21\bin\java.exe" (
  set "JAVA_HOME=C:\Program Files\Java\jdk-21"
  goto RunWrapper
)

if not "%JAVA_HOME%"=="" goto ValidateJavaHome

:ValidateJavaHome
if exist "%JAVA_HOME%\bin\java.exe" goto RunWrapper

if exist "%JAVA_HOME%\java.exe" (
  for %%I in ("%JAVA_HOME%\..") do set "JAVA_HOME=%%~fI"
  goto RunWrapper
)

echo JAVA_HOME is not set and no local JDK 21 fallback was found.
echo Set JAVA_HOME to a valid JDK 21 directory and retry.
exit /b 1

:RunWrapper
pushd "%~dp0"
"%JAVA_HOME%\bin\java.exe" -classpath ".mvn\wrapper\maven-wrapper.jar" "-Dmaven.multiModuleProjectDirectory=%CD%" org.apache.maven.wrapper.MavenWrapperMain -q -DskipTests package
set EXIT_CODE=%ERRORLEVEL%
popd
exit /b %EXIT_CODE%
