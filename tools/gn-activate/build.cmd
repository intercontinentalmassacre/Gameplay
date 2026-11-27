@echo off
rem Run from an x86 Visual Studio developer prompt.
setlocal
pushd "%~dp0"
cl /nologo /std:c11 /O1 /MT /D_CRT_SECURE_NO_WARNINGS /Fo"%TEMP%\gameplay-window-focus.obj" gn-activate.c /link /subsystem:windows /entry:mainCRTStartup /Brepro user32.lib kernel32.lib /out:"..\..\app\src\main\assets\gamescope\gameplay-window-focus.exe"
set "result=%errorlevel%"
popd
exit /b %result%
