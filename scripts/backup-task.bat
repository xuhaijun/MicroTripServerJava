@echo off
rem MicroTrip DB daily backup task (registered via schtasks)
rem All paths ASCII-safe; output appended to logs\backup.log
cd /d D:\FlutterProjects\MicroTripServerJava
if not exist logs mkdir logs
"C:\Program Files\Git\bin\bash.exe" -lc "./scripts/backup.sh --local --keep 14" >> logs\backup.log 2>&1
