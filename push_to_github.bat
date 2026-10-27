@echo off
chcp 65001 >nul
title Aim Master - Git Auto Push with Token
color 0B

echo ======================================================
echo    🎯 Aim Master: أداة الرفع التلقائي إلى GitHub
echo ======================================================
echo.

cd /d "%~dp0"

:: فحص ما إذا كان الرابط يحتوي على Token أم لا
git remote get-url origin > .tmp_url.txt 2>nul
set /p CURRENT_URL=<.tmp_url.txt
del .tmp_url.txt 2>nul

echo الرابط الحالي: %CURRENT_URL%
echo.

:: إذا طلب المستخدم إدخال توكن أو لم يكن التوكن موجوداً
echo ======================================================================
echo 🔑 الصق رمز التوكن (GitHub Token) هنا ثم اضغط Enter:
echo (إذا كنت قد أدخلته سابقاً وتريد المتابعة مباشرة، فقط اضغط Enter)
echo ======================================================================
set /p USER_TOKEN="الـ Token: "

if not "%USER_TOKEN%"=="" (
    echo.
    echo [+] جاري ضبط الـ Token في إعدادات Git...
    git remote set-url origin https://%USER_TOKEN%@github.com/zeussoftdz31-byte/pool.git
    echo [+] تم تحديث الرابط بنجاح!
)

echo.
echo [1/4] فحص حالة الملفات والتعديلات...
git status -s
echo.

echo [2/4] إضافة جميع الملفات إلى الـ Staging...
git add -A
echo.

set "MSG=%~1"
if "%MSG%"=="" set "MSG=Update Aim Master: OpenCV 4.9.0 & Mod Menu UI & Bugfixes"

echo [3/4] حفظ التعديلات في Commit...
git commit -m "%MSG%" >nul 2>&1
echo [+] جاهز للرفع.
echo.

echo [4/4] جاري الرفع إلى مستودع GitHub (git push origin main)...
git push origin main

if %ERRORLEVEL% equ 0 (
    color 0A
    echo.
    echo ======================================================
    echo    ✅ تم الرفع إلى GitHub بنجاح تام!
    echo ======================================================
    echo 🔗 رابط متابعة بناء الـ APK وتحميله فور انتهائه:
    echo 👉 https://github.com/zeussoftdz31-byte/pool/actions
    echo.
) else (
    color 0C
    echo.
    echo ======================================================
    echo    ❌ حدث خطأ أثناء الرفع!
    echo ======================================================
    echo إذا ظهر خطأ 403، تأكد من نسخ الـ Token بالكامل
    echo وتأكد من تفعيل صلاحية Contents / repo و Workflows.
    echo.
)

pause
