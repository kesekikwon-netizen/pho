from PIL import Image, ImageDraw, ImageFont
import os
M='/workspace/gcp-app/design-user-1012/png/'; A='/workspace/gcp-app/build-reports/screens/'; O='/workspace/gcp-app/build-reports/0.1.5/compare/'
os.makedirs(O,exist_ok=True)
pairs=[('02-폴더_첫화면','m02-folder-list','02 폴더(첫 화면)'),('03-새대상-골라서만들기','m03-new-object','03 새 대상'),('04-번호고르기-1–999호','m04-number-127','04 번호 고르기'),
('05-폴더안','m05b-folder-inside-photo','05 폴더 안'),('06-표적재기','m06-targets','06 표적 재기'),('07-재는중','m07-measuring','07 재는 중'),
('08-ID카드_사진첫장','m08-id-card','08 ID 카드'),('09-PC로보내기','m09-handoff','09 PC로 보내기'),('10-유적고르기','m10-sites','10 유적 고르기')]
fp=[p for p in ['/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc','/usr/share/fonts/opentype/noto/NotoSerifCJK-Bold.ttc','/usr/share/fonts/truetype/nanum/NanumGothicBold.ttf'] if os.path.exists(p)]
font=ImageFont.truetype(fp[0],26) if fp else ImageFont.load_default()
H=1000
for m,a,t in pairs:
  im1=Image.open(M+m+'.png').convert('RGB'); im2=Image.open(A+a+'.png').convert('RGB')
  im1=im1.resize((int(im1.width*H/im1.height),H)); im2=im2.resize((int(im2.width*H/im2.height),H))
  W=im1.width+im2.width+60; c=Image.new('RGB',(W,H+70),'white'); d=ImageDraw.Draw(c)
  d.text((20,18),t+'   ·   왼쪽 = 시안   ·   오른쪽 = 0.1.5 (Robolectric)',fill='black',font=font)
  c.paste(im1,(20,60)); c.paste(im2,(im1.width+40,60)); c.save(O+t.split()[0]+'-'+a+'.png')
print(len(pairs))
