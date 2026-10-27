@echo off
chcp 65001 >nul
title Aim Master - Git Auto Push
color 0B

echo ======================================================
echo    🎯 Aim Master: جاري الرفع التلقائي إلى GitHub...
echo ======================================================
echo.

cd /d "%~dp0"

echo [1/4] فحص حالة الملفات والتعديلات...
git status -s
echo.

echo [2/4] إضافة جميع الملفات إلى الـ Staging...
git add -A
echo.

set "MSG=%~1"
if "%MSG%"=="" set "MSG=Update Aim Master: OpenCV 4.9.0 & Mod Menu UI & Bugfixes"

echo [3/4] حفظ التعديلات في Commit...
git commit -m "%MSG%"
echo.

echo [4/4] جاري الرفع إلى مستودع GitHub (git push origin main)...
echo ملاحظة: إذا ظهرت نافذة المتصفح لتسجيل الدخول، يرجى تأكيد الحساب لمرة واحدة فقط.
echo.

git push origin main

if %ERRORLEVEL% equ 0 (
    color 0A
    echo.
    echo ======================================================
    echo    ✅ تم الرفع إلى GitHub بنجاح تام!
    echo ======================================================
    echo 🔗 يمكنك متابعة بناء الـ APK وتحميله فور انتهائه من:
    echo 👉 https://github.com/zeussoftdz31-byte/pool/actions
    echo.
) else (
    color 0C
    echo.
    echo ======================================================
    echo    ❌ حدث خطأ أثناء الرفع!
    echo ======================================================
    echo يرجى التأكد من اتصال الإنترنت وتسجيل الدخول إلى GitHub.
    echo.
)

pause
