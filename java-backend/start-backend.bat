@echo off
rem Starts the SmartLib Java (Spring Boot) backend on http://localhost:3000 — keep this window open.
cd /d "%~dp0"
set PORT=3000
if not exist target\smartlib.jar call mvnw.cmd -q -DskipTests package
start "" http://localhost:3000
java -jar target\smartlib.jar
pause
