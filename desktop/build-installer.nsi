!include "MUI2.nsh"
!include "LogicLib.nsh"

Name "local-sharing"
; 版本用 semver 日期形式（YY.M.D，去前导零无 v），与 CI 产物/exe 内嵌版本逐字符一致
; 例：makensis /DBUILD_VER=26.9.7 build-installer.nsi
!ifndef BUILD_VER
  !define BUILD_VER "26.9.7"
!endif
OutFile "D:\tools\WorkBuddy\Local-sharing\local-sharing\local-sharing_${BUILD_VER}_x64-setup.exe"
InstallDir "$LOCALAPPDATA\Programs\local-sharing"
RequestExecutionLevel user
SetCompressor /SOLID lzma

!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES

; Finish page: "Run local-sharing" + "Create desktop shortcut" (both checked by default)
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_TEXT "Run local-sharing"
!define MUI_FINISHPAGE_RUN_FUNCTION "RunApp"
!define MUI_FINISHPAGE_SHOWREADME
!define MUI_FINISHPAGE_SHOWREADME_TEXT "Create desktop shortcut"
!define MUI_FINISHPAGE_SHOWREADME_FUNCTION CreateDesktopShortcut
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

Function InstallWebView2
  ClearErrors
  ReadRegStr $0 HKCU "Software\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}" "pv"
  ${If} ${Errors}
    ClearErrors
    ReadRegStr $0 HKLM "Software\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}" "pv"
    ${If} ${Errors}
      DetailPrint "WebView2 runtime not found. Installing..."
      ExecWait '"$INSTDIR\MicrosoftEdgeWebview2Setup.exe" /silent /install' $0
      ${If} $0 != 0
        MessageBox MB_OK "WebView2 runtime installation failed (code $0). Please install it manually from https://developer.microsoft.com/en-us/microsoft-edge/webview2/"
      ${EndIf}
    ${EndIf}
  ${EndIf}
FunctionEnd

Section "Main" SEC01
  SetOutPath "$INSTDIR"
  File /r "D:\tools\WorkBuddy\Local-sharing\local-sharing\desktop\portable-staging\*.*"
  CreateDirectory "$SMPROGRAMS\local-sharing"
  CreateShortcut "$SMPROGRAMS\local-sharing\local-sharing.lnk" "$INSTDIR\local-sharing-desktop.exe"
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\local-sharing" "DisplayName" "local-sharing"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\local-sharing" "UninstallString" "$\"$INSTDIR\Uninstall.exe$\""
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\local-sharing" "DisplayIcon" "$\"$INSTDIR\local-sharing-desktop.exe$\""
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\local-sharing" "InstallLocation" "$\"$INSTDIR$\""
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\local-sharing" "Publisher" "local-sharing"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\local-sharing" "DisplayVersion" "${BUILD_VER}"
  Call InstallWebView2
SectionEnd

Section "Uninstall"
  RMDir /r "$INSTDIR"
  RMDir /r "$SMPROGRAMS\local-sharing"
  Delete "$DESKTOP\local-sharing.lnk"
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\local-sharing"
SectionEnd

Function RunApp
  ExecShell "" "$INSTDIR\local-sharing-desktop.exe"
FunctionEnd

Function CreateDesktopShortcut
  CreateShortcut "$DESKTOP\local-sharing.lnk" "$INSTDIR\local-sharing-desktop.exe"
FunctionEnd
