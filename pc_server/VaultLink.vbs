Set WshShell = CreateObject("WScript.Shell")
WshShell.CurrentDirectory = "d:\1_CODING\3_Android Studio Projects\VaultLink\pc_server"
WshShell.Run """d:\1_CODING\1_Aktuelle_Bearbeitung\Talk2Notebooklm\venv\Scripts\python.exe"" ""d:\1_CODING\3_Android Studio Projects\VaultLink\pc_server\sync_server_pro.py""", 0, False
