' Steam Switcher Launcher
' Eloszor leforditja ha kell, majd elinditja - terminalablak nelkul

Dim shell, fso, dir, javaFile, classFile, javaExe, javacExe

Set shell = CreateObject("WScript.Shell")
Set fso   = CreateObject("Scripting.FileSystemObject")

' Az aktualis mappa ahol a .vbs van
dir = fso.GetParentFolderName(WScript.ScriptFullName)

javaFile  = dir & "\SteamSwitcher.java"
classFile = dir & "\SteamSwitcher.class"

' Java keresese
javacExe = FindJava("javac.exe")
javaExe  = FindJava("java.exe")

If javaExe = "" Then
    MsgBox "Nem talalhato a Java telepites!" & vbCrLf & _
           "Telepitsd a Java 23+-t: https://adoptium.net", _
           vbCritical, "Steam Fiokvalto"
    WScript.Quit
End If

' Forditás ha nincs meg a .class, vagy a .java ujabb
Dim needCompile
needCompile = Not fso.FileExists(classFile)

If Not needCompile And fso.FileExists(javaFile) Then
    Dim javaDate, classDate
    javaDate  = fso.GetFile(javaFile).DateLastModified
    classDate = fso.GetFile(classFile).DateLastModified
    If javaDate > classDate Then needCompile = True
End If

If needCompile Then
    If javacExe = "" Then
        MsgBox "Nem talalhato a javac.exe (JDK szukseges a forditashoz)!" & vbCrLf & _
               "Telepitsd a JDK 23+-t: https://adoptium.net", _
               vbCritical, "Steam Fiokvalto"
        WScript.Quit
    End If
    ' Forditas - hatter ablak
    Dim ret
    ret = shell.Run("""" & javacExe & """ """ & javaFile & """", 0, True)
    If ret <> 0 Then
        MsgBox "Forditas sikertelen! (hibakod: " & ret & ")" & vbCrLf & _
               "Ellenorizd hogy a SteamSwitcher.java megvan-e mellette.", _
               vbCritical, "Steam Fiokvalto"
        WScript.Quit
    End If
End If

' Futtatás - terminál nélkül, a mappa beállítva
shell.CurrentDirectory = dir
shell.Run """" & javaExe & """ -cp """ & dir & """ SteamSwitcher", 0, False

' ── Java kereső függvény ──────────────────────────────────────────────────────
Function FindJava(exeName)
    Dim result
    result = ""

    ' 1) PATH-ban van?
    On Error Resume Next
    Dim testRun
    testRun = shell.Run("cmd /c where " & exeName & " > nul 2>&1", 0, True)
    If testRun = 0 Then
        Dim out
        Set out = shell.Exec("cmd /c where " & exeName)
        result = Trim(out.StdOut.ReadLine())
        If result <> "" Then FindJava = result : Exit Function
    End If
    On Error GoTo 0

    ' 2) JAVA_HOME
    Dim javaHome
    javaHome = shell.ExpandEnvironmentStrings("%JAVA_HOME%")
    If javaHome <> "%JAVA_HOME%" Then
        Dim p1
        p1 = javaHome & "\bin\" & exeName
        If fso.FileExists(p1) Then FindJava = p1 : Exit Function
    End If

    ' 3) Fix helyek - Java 17-23
    Dim roots(1)
    roots(0) = "C:\Program Files\Eclipse Adoptium"
    roots(1) = "C:\Program Files\Java"

    Dim i, root, folder
    For i = 0 To 1
        root = roots(i)
        If fso.FolderExists(root) Then
            Dim subFolders
            Set subFolders = fso.GetFolder(root).SubFolders
            Dim sf
            For Each sf In subFolders
                Dim candidate
                candidate = sf.Path & "\bin\" & exeName
                If fso.FileExists(candidate) Then
                    FindJava = candidate
                    Exit Function
                End If
            Next
        End If
    Next

    FindJava = ""
End Function
