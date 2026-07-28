' Export all design elements as DXL via Notes COM (DXLExporter + NoteCollection).
' Usage:
'   cscript //nologo tools\export-design-dxl-com.vbs <out.xml> <server> <nsfPath>
' Example:
'   cscript //nologo tools\export-design-dxl-com.vbs %TEMP%\com-dxl.xml YourDominoServer test\testdiscussion.nsf

Option Explicit
Dim session, db, nc, exporter, dxl, fso, f
Dim outPath, server, nsfPath

If WScript.Arguments.Count < 3 Then
  WScript.Echo "Usage: cscript //nologo export-design-dxl-com.vbs <out.xml> <server> <nsfPath>"
  WScript.Quit 1
End If

outPath = WScript.Arguments(0)
server = WScript.Arguments(1)
nsfPath = WScript.Arguments(2)

On Error Resume Next
Set session = CreateObject("Notes.NotesSession")
If Err.Number <> 0 Then
  WScript.Echo "CreateObject Notes.NotesSession failed: " & Err.Description
  WScript.Quit 2
End If
WScript.Echo "UserName=" & session.UserName

Err.Clear
Set db = session.GetDatabase(server, nsfPath)
If Not db.IsOpen Then
  Call db.Open(server, nsfPath)
End If
If Err.Number <> 0 Or Not db.IsOpen Then
  WScript.Echo "Open failed: " & Err.Description & " IsOpen=" & db.IsOpen
  WScript.Quit 3
End If
WScript.Echo "Title=" & db.Title & " FilePath=" & db.FilePath

Err.Clear
Set nc = db.CreateNoteCollection(False)
nc.SelectAllDesignElements True
nc.BuildCollection
WScript.Echo "DesignNoteCount=" & nc.Count

Set exporter = session.CreateDXLExporter()
If Err.Number <> 0 Then
  WScript.Echo "CreateDXLExporter failed: " & Err.Description
  WScript.Quit 4
End If

dxl = exporter.Export(nc)
If Err.Number <> 0 Or Len(dxl) = 0 Then
  WScript.Echo "Export failed: " & Err.Description & " Len=" & Len(dxl)
  WScript.Quit 5
End If
WScript.Echo "DxlLen=" & Len(dxl)

Set fso = CreateObject("Scripting.FileSystemObject")
Set f = fso.CreateTextFile(outPath, True, True)
f.Write dxl
f.Close
WScript.Echo "Wrote " & outPath
WScript.Quit 0
