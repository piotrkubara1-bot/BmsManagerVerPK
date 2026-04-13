@echo off

if not exist ".env" goto :eof

for /f "usebackq eol=# tokens=1,* delims==" %%A in (".env") do (
    if not "%%A"=="" if not defined %%A set "%%A=%%B"
)
