/*   **********************************************************************  **
 **   Copyright notice                                                       **
 **                                                                          **
 **   (c) 2003-2011 RSSOwl Development Team                                  **
 **   http://www.rssowl.org/                                                 **
 **                                                                          **
 **   All rights reserved                                                    **
 **                                                                          **
 **   This program and the accompanying materials are made available under   **
 **   the terms of the Eclipse Public License 1.0 which accompanies this     **
 **   distribution, and is available at:                                     **
 **   http://www.rssowl.org/legal/epl-v10.html                               **
 **                                                                          **
 **   A copy is found in the file epl-v10.html and important notices to the  **
 **   license from the team is found in the textfile LICENSE.txt distributed **
 **   in this package.                                                       **
 **                                                                          **
 **   This copyright notice MUST APPEAR in all copies of the file!           **
 **                                                                          **
 **   Contributors:                                                          **
 **     RSSOwl - initial API and implementation (bpasero@rssowl.org)         **
 **                                                                          **
 **  **********************************************************************  */

/**
 * The NSIS-Script to create the RSSOwl installer.
 *
 * This x64 variant installs a prebuilt package staged under .\bin and no
 * longer depends on pack200-compressed jars or unpack200.exe.
 *
 * @author bpasero
 * @version 2.2.4
 */

!define VER_DISPLAY "2.2.4"
!define APP_NAME "RSSOwl"
!define APP_EXE "RSSOwl.exe"
!define APP_ICON_SOURCE "icons\product\rssowl.ico"
!define INSTALLER_LICENSE "installer-license.txt"

!include "MUI.nsh"
!include "x64.nsh"

Name "${APP_NAME}"
OutFile "${APP_NAME} Setup ${VER_DISPLAY} x64.exe"
InstallDir "$PROGRAMFILES64\${APP_NAME}"
InstallDirRegKey HKCU "Software\RSSOwl" ""
AllowRootDirInstall true
BrandingText " "
SetCompressor /SOLID lzma
RequestExecutionLevel admin

Var STARTMENU_FOLDER
Var MUI_TEMP

Function "ExecRSSOwl"
  SetOutPath $INSTDIR
  Exec '"$INSTDIR\${APP_EXE}"'
FunctionEnd

VIProductVersion "${VER_DISPLAY}.0"
VIAddVersionKey "ProductName" "${APP_NAME}"
VIAddVersionKey "CompanyName" "RSSOwl Team"
VIAddVersionKey "LegalCopyright" "Benjamin Pasero"
VIAddVersionKey "FileDescription" "${APP_NAME}"
VIAddVersionKey "FileVersion" "${VER_DISPLAY}"

!define MUI_ABORTWARNING
!define MUI_UNABORTWARNING
!define MUI_ICON "${APP_ICON_SOURCE}"
!define MUI_UNICON "${APP_ICON_SOURCE}"
!define MUI_UNFINISHPAGE_NOAUTOCLOSE

!define MUI_LANGDLL_REGISTRY_ROOT "HKCU"
!define MUI_LANGDLL_REGISTRY_KEY "Software\RSSOwl"
!define MUI_LANGDLL_REGISTRY_VALUENAME "Installer Language"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_LICENSE "${INSTALLER_LICENSE}"
!insertmacro MUI_PAGE_DIRECTORY

!define MUI_STARTMENUPAGE_REGISTRY_ROOT "HKCU"
!define MUI_STARTMENUPAGE_REGISTRY_KEY "Software\RSSOwl"
!define MUI_STARTMENUPAGE_REGISTRY_VALUENAME "Start Menu Folder"
!insertmacro MUI_PAGE_STARTMENU Application $STARTMENU_FOLDER

!insertmacro MUI_PAGE_INSTFILES

!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_FUNCTION ExecRSSOwl
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_UNPAGE_FINISH

!insertmacro MUI_LANGUAGE "English"
!insertmacro MUI_LANGUAGE "German"
!insertmacro MUI_LANGUAGE "French"
!insertmacro MUI_LANGUAGE "Spanish"
!insertmacro MUI_LANGUAGE "Italian"
!insertmacro MUI_LANGUAGE "Dutch"
!insertmacro MUI_LANGUAGE "Danish"
!insertmacro MUI_LANGUAGE "Greek"
!insertmacro MUI_LANGUAGE "Russian"
!insertmacro MUI_LANGUAGE "PortugueseBR"
!insertmacro MUI_LANGUAGE "Norwegian"
!insertmacro MUI_LANGUAGE "Ukrainian"
!insertmacro MUI_LANGUAGE "Japanese"
!insertmacro MUI_LANGUAGE "SimpChinese"
!insertmacro MUI_LANGUAGE "Finnish"
!insertmacro MUI_LANGUAGE "Swedish"
!insertmacro MUI_LANGUAGE "Korean"
!insertmacro MUI_LANGUAGE "Polish"
!insertmacro MUI_LANGUAGE "TradChinese"
!insertmacro MUI_LANGUAGE "Hungarian"
!insertmacro MUI_LANGUAGE "Bulgarian"
!insertmacro MUI_LANGUAGE "Czech"
!insertmacro MUI_LANGUAGE "Slovenian"
!insertmacro MUI_LANGUAGE "Turkish"
!insertmacro MUI_LANGUAGE "Thai"
!insertmacro MUI_LANGUAGE "Serbian"
!insertmacro MUI_LANGUAGE "SerbianLatin"
!insertmacro MUI_LANGUAGE "Croatian"
!insertmacro MUI_LANGUAGE "Slovak"

Function .onInit
  !insertmacro MUI_LANGDLL_DISPLAY

  ${IfNot} ${RunningX64}
    MessageBox MB_ICONSTOP|MB_OK "This installer requires a 64-bit version of Windows."
    Abort
  ${EndIf}

  SetRegView 64
FunctionEnd

Function un.onInit
  !insertmacro MUI_UNGETLANGUAGE
  SetRegView 64
FunctionEnd

Section ""
  SetRegView 64
  SetOutPath $INSTDIR

  RMDir /r "$INSTDIR\configuration"
  RMDir /r "$INSTDIR\features"
  RMDir /r "$INSTDIR\plugins"
  RMDir /r "$INSTDIR\jre"

  File /r "bin\*.*"
  File /oname=rssowl.ico "${APP_ICON_SOURCE}"
  File /oname=LICENSE.txt "${INSTALLER_LICENSE}"

  WriteUninstaller "$INSTDIR\Uninstall.exe"

  !insertmacro MUI_STARTMENU_WRITE_BEGIN Application
    CreateDirectory "$SMPROGRAMS\$STARTMENU_FOLDER"
    CreateShortCut "$SMPROGRAMS\$STARTMENU_FOLDER\RSSOwl.lnk" "$INSTDIR\${APP_EXE}" "" "$INSTDIR\rssowl.ico"
    CreateShortCut "$SMPROGRAMS\$STARTMENU_FOLDER\Uninstall.lnk" "$INSTDIR\Uninstall.exe"
    CreateShortcut "$QUICKLAUNCH\RSSOwl.lnk" "$INSTDIR\${APP_EXE}" "" "$INSTDIR\rssowl.ico"
    CreateShortCut "$DESKTOP\RSSOwl.lnk" "$INSTDIR\${APP_EXE}" "" "$INSTDIR\rssowl.ico"
  !insertmacro MUI_STARTMENU_WRITE_END

  WriteINIStr "$SMPROGRAMS\$STARTMENU_FOLDER\Visit Homepage.url" "InternetShortcut" "URL" "http://www.rssowl.org"

  WriteRegStr HKCU "Software\RSSOwl" "" $INSTDIR
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\RSSOwl" "DisplayName" "${APP_NAME} ${VER_DISPLAY} (x64)"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\RSSOwl" "UninstallString" "$INSTDIR\Uninstall.exe"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\RSSOwl" "DisplayIcon" "$INSTDIR\rssowl.ico"

  WriteRegStr HKCR "feed" "" "URL:feed Protocol"
  WriteRegStr HKCR "feed" "URL Protocol" ""
  WriteRegStr HKCR "feed\DefaultIcon" "" "$\"$INSTDIR\${APP_EXE}$\""
  WriteRegStr HKCR "feed\shell\open\command" "" "$\"$INSTDIR\${APP_EXE}$\" $\"%1$\""
SectionEnd

Section "Uninstall"
  SetRegView 64

  Delete "$INSTDIR\.eclipseproduct"
  Delete "$INSTDIR\${APP_EXE}"
  Delete "$INSTDIR\rssowl.ico"
  Delete "$INSTDIR\RSSOwl.ini"
  Delete "$INSTDIR\LICENSE.txt"
  Delete "$INSTDIR\Uninstall.exe"

  RMDir /r "$INSTDIR\configuration"
  RMDir /r "$INSTDIR\features"
  RMDir /r "$INSTDIR\plugins"
  RMDir /r "$INSTDIR\jre"
  RMDir "$INSTDIR"

  !insertmacro MUI_STARTMENU_GETFOLDER Application $MUI_TEMP

  Delete "$SMPROGRAMS\$MUI_TEMP\Uninstall.lnk"
  Delete "$SMPROGRAMS\$MUI_TEMP\Visit Homepage.url"
  Delete "$SMPROGRAMS\$MUI_TEMP\RSSOwl.lnk"
  Delete "$DESKTOP\RSSOwl.lnk"
  Delete "$QUICKLAUNCH\RSSOwl.lnk"

  StrCpy $MUI_TEMP "$SMPROGRAMS\$MUI_TEMP"

  startMenuDeleteLoop:
  RMDir $MUI_TEMP
  GetFullPathName $MUI_TEMP "$MUI_TEMP\.."

  IfErrors startMenuDeleteLoopDone

  StrCmp $MUI_TEMP $SMPROGRAMS startMenuDeleteLoopDone startMenuDeleteLoop
  startMenuDeleteLoopDone:

  DeleteRegKey HKCU "Software\RSSOwl"
  DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\RSSOwl"
  DeleteRegKey HKCR "feed"
SectionEnd
