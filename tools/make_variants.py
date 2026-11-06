"""يولّد صيغ GLB احتياطية مبسّطة داخل assets (يجرّبها التطبيق بالترتيب إن رفض Filament الصيغة الأساسية).
الاستخدام: python3 make_variants.py <مسار assets>
  models   : الصيغة الكاملة (ألوان رؤوس + حركة)     -> make_characters.py
  models_b : بدون ألوان رؤوس
  models_c : بدون ألوان رؤوس وبدون حركة
  models_d : كرة بسيطة واحدة (اختبار أن Filament يقرأ أي ملف)
"""
import os, sys
import glbgen
from glbgen import Model
import make_characters as mc

ASSETS = sys.argv[1] if len(sys.argv) > 1 else "../app/src/main/assets"


def write(dirname, base, data):
    d = os.path.join(ASSETS, dirname)
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, base + ".glb"), "wb") as f:
        f.write(data)


for dirname, opts in (("models_b", {"colors": False, "anim": True}),
                      ("models_c", {"colors": False, "anim": False})):
    glbgen.OPTIONS.update(opts)
    for base, (name, fn, persona) in mc.PERSONAS.items():
        write(dirname, base, fn().build())
    print(dirname, "ok")

glbgen.OPTIONS.update({"colors": False, "anim": False})
m = Model("basic")
m.sph(m.root, "ball", 0x4CAF50, (0, 0.3, 0), 0.3)
write("models_d", "basic", m.build())
print("models_d ok")
