@echo off
@setlocal
set ERROR_CODE=0
if not "%JAVA_HOME%"=="" goto OkJHome
echo Error: JAVA_HOME not set. Please set JAVA_HOME to your JDK directory.
goto error
:OkJHome
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo Error: JAVA_HOME does not point to a valid JDK. JAVA_HOME=%JAVA_HOME%
  goto error
)
set MAVEN_PROJECTBASEDIR=%~dp0
cd /d "%MAVEN_PROJECTBASEDIR%"
set WRAPPER_JAR="%MAVEN_PROJECTBASEDIR%.mvn\wrapper\maven-wrapper.jar"
set WRAPPER_URL=https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar
if exist %WRAPPER_JAR% goto run
echo Downloading Maven Wrapper...
powershell -NoProfile -Command "& { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; $jar = (Get-Location).Path + '\.mvn\wrapper\maven-wrapper.jar'; (New-Object Net.WebClient).DownloadFile('%WRAPPER_URL%', $jar) }"
if not exist %WRAPPER_JAR% (
  echo Failed to download Maven Wrapper. Check your network or set MAVEN_HOME and use: "%MAVEN_HOME%\bin\mvn.cmd" -q -DskipTests package
  goto error
)
:run
"%JAVA_HOME%\bin\java.exe" -classpath %WRAPPER_JAR% "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%" org.apache.maven.wrapper.MavenWrapperMain %*
set ERROR_CODE=%ERRORLEVEL%
goto end
:error
set ERROR_CODE=1
:end
exit /b %ERROR_CODE%
