Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

root = fso.GetParentFolderName(WScript.ScriptFullName) & "\.."
shell.CurrentDirectory = fso.GetAbsolutePathName(root)

shell.Run "cmd /c ""scripts\run_bg.cmd""", 0, False
