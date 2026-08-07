@echo off
set INSTALLER=%~1
set APP_EXE=%~2
"%INSTALLER%" /quiet /norestart
start "" "%APP_EXE%"
