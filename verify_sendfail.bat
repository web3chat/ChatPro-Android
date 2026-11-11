@echo off
set JAVA_HOME=D:\Android\Android Studio\jre
set PATH=%JAVA_HOME%\bin;%PATH%
cd /d F:\FlutterProjects\ChatPro-master
del /q verify_build.log 2>nul
del /q verify_result.txt 2>nul
call gradlew.bat :chat-core:compileChat33proDebugKotlin :chat-ui:compileChat33proDebugKotlin :app:compileChat33proDebugKotlin --console=plain > verify_build.log 2>&1
set EXITCODE=%ERRORLEVEL%
echo GRADLE_EXIT=%EXITCODE% >> verify_build.log
findstr /C:"BUILD SUCCESSFUL" /C:"BUILD FAILED" /C:"FAILURE:" /C:"e: " verify_build.log > verify_result.txt
echo GRADLE_EXIT=%EXITCODE% >> verify_result.txt
