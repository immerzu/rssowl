@echo off
echo This legacy helper is no longer used for the x64 build.
echo.
echo RSSOwl now ships as an unpacked x64 product with a bundled jre\ directory,
echo so pack200.exe and unpack200.exe are no longer part of the installer path.
echo.
echo Use ..\..\..\scripts\build_nsis_installer.ps1 to stage the current x64 ZIP
echo into org.rssowl.ui\bin and optionally compile rssowl_installer.nsi.
