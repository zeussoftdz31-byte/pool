#!/bin/bash
# ==============================================================================
# 🎯 Aim Master - Automated Git Push Script with Token Support
# ==============================================================================

GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${CYAN}======================================================${NC}"
echo -e "${CYAN}   🎯 Aim Master: أداة الرفع التلقائي إلى GitHub     ${NC}"
echo -e "${CYAN}======================================================${NC}\n"

cd "$(dirname "$0")" || exit 1

echo -e "${YELLOW}======================================================================${NC}"
echo -e "${YELLOW}🔑 الصق رمز التوكن (GitHub Token) هنا ثم اضغط Enter:${NC}"
echo -e "${YELLOW}(إذا كنت قد ضبطته سابقاً، فقط اضغط Enter للمتابعة):${NC}"
echo -e "${YELLOW}======================================================================${NC}"
read -p "الـ Token: " USER_TOKEN

if [ -n "$USER_TOKEN" ]; then
    echo -e "\n${CYAN}[+] جاري ضبط الـ Token في إعدادات Git...${NC}"
    git remote set-url origin "https://${USER_TOKEN}@github.com/zeussoftdz31-byte/pool.git"
    echo -e "${GREEN}[+] تم تحديث الرابط بنجاح!${NC}\n"
fi

echo -e "${YELLOW}[1/4] فحص حالة الملفات...${NC}"
git status -s

echo -e "\n${YELLOW}[2/4] إضافة جميع الملفات إلى الـ Staging...${NC}"
git add -A

COMMIT_MSG="${1:-Update Aim Master: OpenCV 4.9.0 & Mod Menu UI & Bugfixes}"
echo -e "\n${YELLOW}[3/4] حفظ التعديلات...${NC}"
git commit -m "$COMMIT_MSG" >/dev/null 2>&1

echo -e "\n${YELLOW}[4/4] جاري الرفع إلى مستودع GitHub (git push origin main)...${NC}"
if git push origin main; then
    echo -e "\n${GREEN}======================================================${NC}"
    echo -e "${GREEN}   ✅ تم الرفع إلى GitHub بنجاح تام!                 ${NC}"
    echo -e "${GREEN}======================================================${NC}"
    echo -e "${CYAN}🔗 رابط متابعة بناء الـ APK وتحميله فور انتهائه:${NC}"
    echo -e "${YELLOW}👉 https://github.com/zeussoftdz31-byte/pool/actions${NC}\n"
else
    echo -e "\n${RED}======================================================${NC}"
    echo -e "${RED}   ❌ حدث خطأ أثناء الرفع!                            ${NC}"
    echo -e "${RED}======================================================${NC}"
fi

read -p "اضغط Enter للإغلاق..."
