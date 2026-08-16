# Stop ShizukuTranslate services by command line match.
# NEVER use taskkill /im java.exe — the server also runs Minecraft
# (SpikeSpigot.jar) and a blanket kill would take it down too.

# Backend: translator.jar (java.exe / javaw.exe)
Get-CimInstance Win32_Process |
  Where-Object { $_.CommandLine -like '*translator.jar*' -and $_.Name -like 'java*' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

# OCR worker: ocr_server.py
Get-CimInstance Win32_Process |
  Where-Object { $_.CommandLine -like '*ocr_server.py*' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
