#!/bin/bash
# ==============================================================================
# 🎯 Aim Master - Automated Git Push Script
# هذا السكربت يقوم برفع جميع التغييرات تلقائياً إلى GitHub وبدء بناء الـ APK
# ==============================================================================

GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${CYAN}======================================================${NC}"
echo -e "${CYAN}   🎯 Aim Master: جاري الرفع التلقائي إلى GitHub...   ${NC}"
echo -e "${CYAN}======================================================${NC}"

# انتقل إلى مجلد السكربت الحالي لضمان التواجد داخل المشروع
cd "$(dirname "$0")" || exit 1

# 1. التحقق من حالة الملفات
echo -e "\n${YELLOW}[1/4] فحص حالة الملفات والتعديلات...${NC}"
git status -s

# 2. إضافة جميع الملفات المعدلة والجديدة
echo -e "\n${YELLOW}[2/4] إضافة جميع الملفات إلى الـ Staging...${NC}"
git add -A

# 3. عمل Commit إذا كانت هناك تعديلات غير محفوظة
COMMIT_MSG="${1:-Update Aim Master: OpenCV 4.9.0 & Mod Menu UI & Bugfixes}"
if ! git diff-index --quiet HEAD --; then
    echo -e "${YELLOW}[3/4] حفظ التعديلات (Commit): '${COMMIT_MSG}'...${NC}"
    git commit -m "$COMMIT_MSG"
else
    echo -e "${GREEN}[3/4] جميع التعديلات محفوظة بالفعل في الـ Commits السابقة.${NC}"
fi

# 4. رفع التعديلات إلى فرع main
echo -e "\n${YELLOW}[4/4] جاري الرفع إلى مستودع GitHub (git push origin main)...${NC}"
echo -e "${CYAN}ملاحظة: إذا ظهرت نافذة المتصفح لتسجيل الدخول، يرجى تأكيد الحساب لمرة واحدة فقط.${NC}"

if git push origin main; then
    echo -e "\n${GREEN}======================================================${NC}"
    echo -e "${GREEN}   ✅ تم الرفع إلى GitHub بنجاح تام!                 ${NC}"
    echo -e "${GREEN}======================================================${NC}"
    echo -e "${CYAN}🔗 يمكنك متابعة بناء الـ APK وتحميله فور انتهائه من الرابط التالي:${NC}"
    echo -e "${YELLOW}👉 https://github.com/zeussoftdz31-byte/pool/actions${NC}\n"
else
    echo -e "\n${RED}======================================================${NC}"
    echo -e "${RED}   ❌ حدث خطأ أثناء الرفع!                            ${NC}"
    echo -e "${RED}======================================================${NC}"
    echo -e "${YELLOW}تأكد من اتصالك بالإنترنت وصلاحيات حساب GitHub.${NC}\n"
fi

read -p "اضغط Enter للإغلاق..."
