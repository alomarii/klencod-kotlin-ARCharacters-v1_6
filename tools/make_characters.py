import math, os, sys
from glbgen import Model, on_sphere, face_point

OUT = sys.argv[1] if len(sys.argv) > 1 else "out2"
os.makedirs(OUT, exist_ok=True)

BLACK = 0x15151A
WHITE = 0xFFFFFF
SKIN = 0xF6CBA4
PINK = 0xFF9DB0
MOUTH = 0x3B2326


# ───────────── helpers ─────────────

def frame(m, parent, R, az, el, push=0.0, roll=0, name="f"):
    p, e = face_point(R, az, el, push)
    return m.add(name, parent, t=p, r=(e[0], e[1], roll))


def eye(m, parent, R, az, el, w, h, iris=None, roll=0, hl=True, push=0.0):
    """Big glossy cartoon eye sitting on the head surface (frame local +Z points outward)."""
    f = frame(m, parent, R, az, el, push, roll, "eye")
    d = min(w, h) * 0.55
    m.sph(f, "eyebase", BLACK, (0, 0, -d * 0.2), (w, h, d), rough=0.12)
    zf = d * 0.8
    if iris is not None:
        m.sph(f, "iris", iris, (0, 0, d * 0.12), (w * 0.82, h * 0.84, d * 0.82), rough=0.18)
        m.sph(f, "pupil", BLACK, (0, 0, d * 0.42), (w * 0.42, h * 0.52, d * 0.62), rough=0.1)
        zf = d * 1.0
    if hl:
        m.sph(f, "hl1", WHITE, (w * 0.3, h * 0.36, zf - w * 0.12), w * 0.27, rough=0.05, emissive=0x888888)
        m.sph(f, "hl2", WHITE, (-w * 0.32, -h * 0.34, zf - w * 0.1), w * 0.13, rough=0.05, emissive=0x888888)
    m.eyes.append(f)
    return f


def eyes(m, parent, R, az, el, w, h, iris=None, tilt=0):
    for sx in (-1, 1):
        eye(m, parent, R, sx * az, el, w, h, iris, roll=-sx * tilt)


def smile(m, parent, R, az, el, rad, tube=0.008, arc=130, color=MOUTH, push=0.0, emissive=None):
    f = frame(m, parent, R, az, el, push, 0, "mouth")
    m.tor(f, "smile", color, (0, rad * 0.7, tube * 0.3), rad, tube, arc, rough=0.5, emissive=emissive)
    return f


def blush(m, parent, R, az, el, w, h, color=PINK):
    for sx in (-1, 1):
        f = frame(m, parent, R, sx * az, el, 0.0, 0, "blush")
        m.sph(f, "blush", color, (0, 0, -0.004), (w, h, 0.012), rough=0.9)


def cat_mouth(m, f, rad=0.032, y=-0.012):
    for sx in (-1, 1):
        m.tor(f, "m", MOUTH, (sx * rad * 0.98, y + rad * 0.7, 0.002), rad, 0.0065, 150, rough=0.5)


def idle(m, body, amp=0.02, period=2.0):
    m.anim_pos(body, [0, period / 2, period], [(0, 0, 0), (0, amp, 0), (0, 0, 0)])


def look_around(m, head, yaw=10, roll=3, period=6.0):
    q = period / 4
    m.anim_rot(head, [0, q, 2 * q, 3 * q, period],
               [(0, 0, 0), (0, yaw, roll), (0, 0, 0), (0, -yaw, -roll), (0, 0, 0)])


def wave(m, arm, sign=1, rest=0, hi=140, period=4.0):
    t = [0, 1.2, 1.7, 2.2, 2.7, 3.2, period]
    lo = hi - 22
    v = [rest, hi, lo, hi, lo, hi, rest]
    m.anim_rot(arm, t, [(0, 0, sign * a) for a in v])


def sway(m, node, amp=15, period=3.0, axis="y"):
    q = period / 4
    vals = [0, amp, 0, -amp, 0]
    eul = [(0, a, 0) if axis == "y" else (a, 0, 0) if axis == "x" else (0, 0, a) for a in vals]
    m.anim_rot(node, [0, q, 2 * q, 3 * q, period], eul)


def blink(m, period=4.0, start=2.9):
    for i, f in enumerate(m.eyes):
        t0 = start
        m.anim_scale(f, [0, t0, t0 + 0.07, t0 + 0.14, period],
                     [(1, 1, 1), (1, 1, 1), (1, 0.08, 1), (1, 1, 1), (1, 1, 1)])


def finish(m, body, head=None, amp=0.02, period=2.0, yaw=10, roll=3):
    idle(m, body, amp, period)
    if head is not None:
        look_around(m, head, yaw, roll, 6.0)
    blink(m)
    return m


# ───────────── 1. روبو ─────────────

def robo():
    m = Model("robo")
    body = m.add("body", m.root)
    SH, AC, DK, CY, OR = 0xEAF0F6, 0x27B5C6, 0x2B3340, 0x5DF2FF, 0xFF8A3D
    for sx in (-1, 1):
        m.cap(body, "leg", DK, (sx * 0.13, 0.12, 0), 0.06, 0.26, metal=0.3, rough=0.4)
        m.rbx(body, "foot", AC, (sx * 0.13, 0.04, 0.05), (0.19, 0.09, 0.28), rad=0.4, rough=0.35, metal=0.2)
    m.rbx(body, "torso", SH, (0, 0.45, 0), (0.5, 0.42, 0.38), rad=0.32, rough=0.3, metal=0.25)
    m.rbx(body, "panel", DK, (0, 0.48, 0.18), (0.32, 0.22, 0.06), rad=0.4)
    m.rbx(body, "screen", CY, (0, 0.485, 0.212), (0.25, 0.14, 0.02), rad=0.45, emissive=CY)
    m.tor(body, "ekg", DK, (0, 0.49, 0.226), 0.04, 0.008, 360)
    for x, c in ((-0.1, 0xFF5A5A), (0, 0xFFD23F), (0.1, 0x5BE37D)):
        m.sph(body, "btn", c, (x, 0.34, 0.19), 0.027, rough=0.3)
    m.rbx(body, "belt", AC, (0, 0.27, 0), (0.52, 0.05, 0.4), rad=0.4, rough=0.3)
    head = m.add("head", body, t=(0, 0.88, 0))
    m.rbx(head, "skull", SH, (0, 0, 0), (0.74, 0.52, 0.56), rad=0.34, rough=0.28, metal=0.25)
    m.rbx(head, "visor", DK, (0, 0.0, 0.265), (0.6, 0.36, 0.08), rad=0.4, rough=0.15)
    for sx in (-1, 1):
        f = m.add("eye", head, t=(sx * 0.14, 0.03, 0.31))
        m.rbx(f, "e", CY, (0, 0, 0), (0.12, 0.14, 0.03), rad=0.5, emissive=CY)
        m.eyes.append(f)
        m.sph(head, "blush", OR, (sx * 0.24, -0.07, 0.3), (0.05, 0.03, 0.01), emissive=0x702A10)
        m.cyl(head, "ear", AC, (sx * 0.38, 0, 0), 0.1, 0.07, r=(0, 0, 90), rough=0.3)
        m.cyl(head, "earcap", DK, (sx * 0.42, 0, 0), 0.06, 0.02, r=(0, 0, 90))
    m.tor(head, "smile", CY, (0, -0.03, 0.305), 0.075, 0.011, 130, emissive=CY)
    m.cyl(head, "antenna", DK, (0, 0.31, 0), 0.012, 0.1)
    m.sph(head, "bulb", OR, (0, 0.38, 0), 0.05, emissive=0xFF6A10, rough=0.2)
    arms = {}
    for sx, nm in ((1, "armR"), (-1, "armL")):
        a = m.add(nm, body, t=(sx * 0.32, 0.58, 0))
        m.sph(a, "shoulder", AC, (0, 0, 0), 0.08, rough=0.3)
        m.cap(a, "arm", SH, (0, -0.14, 0), 0.058, 0.3, rough=0.3, metal=0.2)
        m.sph(a, "hand", DK, (0, -0.29, 0), 0.07, rough=0.4)
        arms[nm] = a
    idle(m, body, 0.02)
    look_around(m, head, 14, 0)
    blink(m)
    wave(m, arms["armR"], 1)
    return m


# ───────────── 2. ميمي (قطة) ─────────────

def mimi():
    m = Model("mimi")
    body = m.add("body", m.root)
    OR, CR, DO = 0xF5A042, 0xFFF0D6, 0xC9701E
    m.sph(body, "torso", OR, (0, 0.27, -0.01), (0.24, 0.27, 0.23))
    m.sph(body, "chest", CR, (0, 0.25, 0.14), (0.16, 0.2, 0.1))
    for sx in (-1, 1):
        m.cap(body, "foreleg", OR, (sx * 0.095, 0.12, 0.15), 0.065, 0.24)
        m.sph(body, "forepaw", CR, (sx * 0.095, 0.035, 0.175), (0.07, 0.045, 0.085))
        m.sph(body, "haunch", OR, (sx * 0.19, 0.14, -0.05), (0.1, 0.13, 0.15))
        m.sph(body, "hindpaw", CR, (sx * 0.18, 0.035, 0.07), (0.07, 0.04, 0.1))
    m.cyl(body, "stripe1", DO, (0, 0.38, -0.2), (0.15, 0.02), 0.03, r=(-70, 0, 0))
    R = (0.34, 0.29, 0.29)
    head = m.add("head", body, t=(0, 0.69, 0.04))
    m.sph(head, "skull", OR, (0, 0, 0), R)
    for sx in (-1, 1):
        p = on_sphere((0, 0, 0), R, sx * 42, 52)
        e = m.add("ear", head, t=(p[0], p[1] + 0.03, p[2] - 0.02), r=(-8, sx * 10, -sx * 22))
        m.cone(e, "ear", OR, (0, 0.07, 0), (0.105, 0.05), 0.2)
        m.cone(e, "inner", PINK, (0, 0.06, 0.03), (0.06, 0.03), 0.14)
    eyes(m, head, R, 27, 7, 0.07, 0.085, iris=0x8EE06E)
    mz = frame(m, head, R, 0, -16, -0.03)
    m.sph(mz, "muzzle", CR, (0, 0, 0.0), (0.105, 0.075, 0.06))
    m.sph(mz, "nose", 0xF2889C, (0, 0.03, 0.055), (0.03, 0.022, 0.02), rough=0.3)
    cat_mouth(m, mz, 0.03, -0.012)
    for sx in (-1, 1):
        for k in (-1, 0, 1):
            m.cyl(mz, "whisker", WHITE, (sx * 0.17, 0.0 + k * 0.026, 0.0), 0.0042, 0.2, r=(0, 0, 90 + sx * k * 9))
    for az in (-9, 0, 9):
        m.cap(head, "stripe", DO, on_sphere((0, 0, 0), R, az, 68, -0.004), 0.012, 0.07, r=(0, 0, 0))
    blush(m, head, R, 44, -6, 0.05, 0.03)
    tail = m.add("tail", body, t=(0, 0.14, -0.2))
    for k in range(8):
        t_ = k / 7
        y = 0.02 + 0.46 * t_
        z = -0.06 - 0.12 * math.sin(math.pi * t_ * 0.9)
        m.sph(tail, "tail", CR if k == 7 else OR, (0, y, z), 0.062 - 0.012 * t_)
    finish(m, body, head, 0.015, 2.0, 10, 4)
    sway(m, tail, 16, 3.0)
    return m


# ───────────── 3. لولو (أرنب) ─────────────

def lolo():
    m = Model("lolo")
    body = m.add("body", m.root)
    W, SH = 0xF8F5F1, 0xFFFFFF
    m.sph(body, "torso", W, (0, 0.27, 0), (0.24, 0.27, 0.23))
    m.sph(body, "belly", SH, (0, 0.25, 0.14), (0.16, 0.19, 0.1))
    m.sph(body, "tail", SH, (0, 0.17, -0.25), 0.1)
    for sx in (-1, 1):
        m.cap(body, "arm", W, (sx * 0.2, 0.28, 0.12), 0.05, 0.2, r=(15, 0, sx * 18))
        m.sph(body, "foot", W, (sx * 0.13, 0.04, 0.1), (0.085, 0.05, 0.15))
        m.sph(body, "pad", PINK, (sx * 0.13, 0.03, 0.19), (0.04, 0.022, 0.05))
    R = (0.31, 0.28, 0.27)
    head = m.add("head", body, t=(0, 0.66, 0.04))
    m.sph(head, "skull", W, (0, 0, 0), R)
    ears = []
    for sx in (-1, 1):
        p = on_sphere((0, 0, 0), R, sx * 20, 66, -0.03)
        pv = m.add("ear", head, t=p, r=(0, 0, -sx * 10))
        m.cap(pv, "ear", W, (0, 0.23, 0), (0.07, 0.04), 0.5)
        m.cap(pv, "inner", PINK, (0, 0.23, 0.02), (0.042, 0.02), 0.4)
        ears.append((pv, sx))
    eyes(m, head, R, 27, 6, 0.062, 0.078)
    mz = frame(m, head, R, 0, -12, -0.01)
    m.sph(mz, "nose", 0xF2889C, (0, 0.02, 0.03), (0.03, 0.024, 0.022), rough=0.3)
    cat_mouth(m, mz, 0.028, -0.02)
    m.rbx(mz, "teeth", WHITE, (0, -0.058, 0.01), (0.06, 0.05, 0.012), rad=0.3)
    blush(m, head, R, 44, -8, 0.05, 0.03)
    finish(m, body, head, 0.025, 1.6, 12, 4)
    for pv, sx in ears:
        t = [0, 2.0, 2.2, 2.4, 2.6, 5.0]
        a0 = -sx * 10
        m.anim_rot(pv, t, [(0, 0, a0), (0, 0, a0), (0, 0, -sx * 30), (0, 0, a0), (0, 0, -sx * 24), (0, 0, a0)])
    return m


# ───────────── 4. دبدوب (دب) ─────────────

def bear_like(name, fur, light, patch=None, bamboo=False):
    m = Model(name)
    body = m.add("body", m.root)
    limb = patch or fur
    m.sph(body, "torso", fur, (0, 0.34, 0), (0.31, 0.32, 0.27))
    m.sph(body, "belly", light, (0, 0.32, 0.19), (0.22, 0.23, 0.1))
    m.sph(body, "tail", limb if patch else fur, (0, 0.22, -0.28), 0.07)
    for sx in (-1, 1):
        m.sph(body, "leg", limb, (sx * 0.17, 0.09, 0.07), (0.12, 0.1, 0.16))
        m.sph(body, "sole", light if not patch else 0x4A4A52, (sx * 0.17, 0.085, 0.2), (0.07, 0.06, 0.035))
    R = (0.33, 0.3, 0.29)
    head = m.add("head", body, t=(0, 0.8, 0.03))
    m.sph(head, "skull", fur if not patch else WHITE, (0, 0, 0), R)
    for sx in (-1, 1):
        p = on_sphere((0, 0, 0), R, sx * 52, 48, 0.015)
        ear_col = limb
        m.sph(head, "ear", ear_col, p, 0.1)
        fe = frame(m, head, (0.33, 0.3, 0.29), sx * 52, 48, 0.015)
        m.sph(fe, "inner", light if not patch else 0x4A4A52, (0, 0, 0.065), (0.058, 0.058, 0.03))
    if patch:
        for sx in (-1, 1):
            f = frame(m, head, R, sx * 28, 4, -0.012, -sx * 22)
            m.sph(f, "patch", patch, (0, 0, 0), (0.075, 0.1, 0.035), rough=0.5)
            m.sph(f, "e", BLACK, (0, 0, 0.022), (0.04, 0.05, 0.022), rough=0.15)
            m.sph(f, "hl", WHITE, (0.012, 0.016, 0.04), 0.012, rough=0.05, emissive=0x888888)
            m.eyes.append(f)
    else:
        eyes(m, head, R, 29, 6, 0.052, 0.062)
    mz = frame(m, head, R, 0, -14, -0.03)
    m.sph(mz, "muzzle", light, (0, 0, 0), (0.125, 0.095, 0.075))
    m.sph(mz, "nose", BLACK, (0, 0.035, 0.065), (0.04, 0.03, 0.025), rough=0.25)
    m.cyl(mz, "line", MOUTH, (0, -0.01, 0.068), 0.004, 0.035)
    cat_mouth(m, mz, 0.03, -0.028)
    blush(m, head, R, 48, -10, 0.05, 0.03)
    arms = {}
    for sx, nm in ((1, "armR"), (-1, "armL")):
        a = m.add(nm, body, t=(sx * 0.3, 0.52, 0.03), r=(0, 0, sx * 14))
        m.cap(a, "arm", limb, (0, -0.13, 0), 0.095, 0.34)
        arms[nm] = a
    finish(m, body, head, 0.018, 2.4, 9, 4)
    t = [0, 1.2, 1.7, 2.2, 2.7, 3.2, 4.0]
    m.anim_rot(arms["armR"], t, [(0, 0, a) for a in (14, 140, 118, 140, 118, 140, 14)])
    return m, body, arms


def dabdoub():
    m, body, arms = bear_like("dabdoub", 0x8E5B2D, 0xE2BE92)
    RED = 0xD64545
    m.sph(body, "knot", RED, (0, 0.58, 0.2), 0.03)
    for sx in (-1, 1):
        m.cone(body, "bow", RED, (sx * 0.065, 0.58, 0.2), (0.05, 0.03), 0.09, r=(0, 0, sx * -90))
    return m


def panda():
    m, body, arms = bear_like("panda", 0xF6F3EE, 0xFFFFFF, patch=0x24242A)
    GR = 0x6FBF5A
    a = arms["armR"]
    m.cyl(a, "bamboo", GR, (0.0, -0.22, 0.07), 0.026, 0.52, r=(0, 0, 0), rough=0.5)
    for y in (-0.42, -0.24, -0.06):
        m.tor(a, "node", 0x4F9A42, (0.0, y, 0.07), 0.028, 0.008, 360, r=(90, 0, 0))
    m.cone(a, "leaf", 0x8AD36B, (0.05, -0.05, 0.07), (0.05, 0.012), 0.16, r=(0, 0, -60))
    m.cone(a, "leaf", 0x8AD36B, (-0.05, -0.12, 0.07), (0.05, 0.012), 0.14, r=(0, 0, 60))
    return m


# ───────────── 5. بنجو (بطريق) ─────────────

def pingo():
    m = Model("pingo")
    body = m.add("body", m.root)
    DK, WH, OR, RD = 0x26344A, 0xFAFAFA, 0xFFA21F, 0xD94848
    m.sph(body, "torso", DK, (0, 0.46, 0), (0.3, 0.44, 0.28))
    m.sph(body, "belly", WH, (0, 0.43, 0.1), (0.23, 0.36, 0.2))
    for sx in (-1, 1):
        m.sph(body, "foot", OR, (sx * 0.12, 0.03, 0.09), (0.09, 0.03, 0.15))
        for k in (-1, 0, 1):
            m.sph(body, "toe", OR, (sx * 0.12 + k * 0.045, 0.03, 0.22), (0.03, 0.025, 0.04))
    R = (0.27, 0.24, 0.24)
    head = m.add("head", body, t=(0, 0.95, 0.02))
    m.sph(head, "skull", DK, (0, 0, 0), R)
    face = frame(m, head, R, 0, -4, -0.03)
    m.sph(face, "face", WH, (0, 0, 0), (0.2, 0.17, 0.07))
    eyes(m, head, R, 23, 6, 0.058, 0.072)
    bk = frame(m, head, R, 0, -10, 0.0)
    m.cone(bk, "beakTop", OR, (0, 0, 0.055), (0.062, 0.04), 0.12, r=(90, 0, 0), rough=0.4)
    m.cone(bk, "beakBot", 0xE88A10, (0, -0.02, 0.04), (0.05, 0.03), 0.09, r=(90, 0, 0), rough=0.4)
    blush(m, head, R, 40, -8, 0.045, 0.028)
    m.tor(body, "scarf", RD, (0, 0.77, 0), 0.215, 0.05, 360, r=(90, 0, 0), rough=0.9)
    m.rbx(body, "scarfTail", RD, (0.1, 0.66, 0.22), (0.085, 0.22, 0.04), r=(0, 0, -6), rad=0.4, rough=0.9)
    m.rbx(body, "stripe", 0xF2C94C, (0.1, 0.7, 0.243), (0.085, 0.03, 0.008), r=(0, 0, -6), rad=0.4)
    arms = {}
    for sx, nm in ((1, "flipR"), (-1, "flipL")):
        a = m.add(nm, body, t=(sx * 0.29, 0.64, 0), r=(0, 0, sx * 16))
        m.cap(a, "flipper", DK, (0, -0.14, 0), (0.05, 0.11), 0.36)
        arms[nm] = a
    finish(m, body, head, 0.02, 1.8, 10, 4)
    wave(m, arms["flipR"], 1, rest=16, hi=125)
    return m


# ───────────── 6. زوزو (فضائي) ─────────────

def zozo():
    m = Model("zozo")
    body = m.add("body", m.root)
    GR, DG, PU, CY = 0x82DC5E, 0x58AE40, 0x6C42A8, 0x55E8FF
    m.rbx(body, "torso", PU, (0, 0.32, 0), (0.34, 0.34, 0.28), rad=0.5, rough=0.45)
    m.sph(body, "chest", CY, (0, 0.34, 0.14), (0.045, 0.045, 0.02), emissive=CY)
    m.rbx(body, "belt", 0xFFD23F, (0, 0.2, 0), (0.35, 0.04, 0.29), rad=0.5)
    for sx in (-1, 1):
        m.cap(body, "leg", GR, (sx * 0.08, 0.09, 0), 0.052, 0.2)
        m.sph(body, "foot", DG, (sx * 0.08, 0.03, 0.05), (0.07, 0.04, 0.1))
    R = (0.37, 0.34, 0.32)
    head = m.add("head", body, t=(0, 0.78, 0))
    m.sph(head, "skull", GR, (0, 0, 0), R)
    eyes(m, head, R, 31, 0, 0.1, 0.14, tilt=18)
    smile(m, head, R, 0, -40, 0.05, 0.008, 140)
    blush(m, head, R, 52, -22, 0.05, 0.028, 0x4FBF6A)
    for sx in (-1, 1):
        m.cap(head, "antenna", DG, (sx * 0.12, 0.38, 0), 0.013, 0.22, r=(0, 0, -sx * 16))
        m.sph(head, "bulb", 0xFFE14D, (sx * 0.17, 0.5, 0), 0.045, emissive=0xFFC820, rough=0.2)
        m.cone(head, "fin", DG, (sx * 0.34, 0.02, 0), (0.06, 0.02), 0.14, r=(0, 0, -sx * 90))
    arms = {}
    for sx, nm in ((1, "armR"), (-1, "armL")):
        a = m.add(nm, body, t=(sx * 0.18, 0.42, 0), r=(0, 0, sx * 22))
        m.cap(a, "arm", GR, (0, -0.1, 0), 0.04, 0.24)
        m.sph(a, "hand", DG, (0, -0.22, 0), 0.058)
        arms[nm] = a
    finish(m, body, head, 0.025, 1.7, 12, 6)
    wave(m, arms["armL"], -1, rest=22, hi=135)
    return m


# ───────────── 7. نار (تنين) ─────────────

def nar():
    m = Model("nar")
    body = m.add("body", m.root)
    RD, CR, OR, YL = 0xE5503A, 0xF8DB95, 0xF59A2B, 0xFFCB3D
    m.sph(body, "torso", RD, (0, 0.34, 0), (0.28, 0.3, 0.26))
    m.sph(body, "belly", CR, (0, 0.32, 0.13), (0.2, 0.25, 0.14))
    for i, y in enumerate((0.42, 0.34, 0.26)):
        m.sph(body, "plate", 0xF1C877, (0, y, 0.25 - i * 0.003), (0.15 - i * 0.015, 0.025, 0.02))
    for sx in (-1, 1):
        m.sph(body, "foot", RD, (sx * 0.14, 0.06, 0.11), (0.1, 0.065, 0.14))
        for k in (-1, 0, 1):
            m.cone(body, "claw", 0xFFF0C8, (sx * 0.14 + k * 0.04, 0.045, 0.245), 0.018, 0.045, r=(90, 0, 0))
        m.cap(body, "arm", RD, (sx * 0.27, 0.36, 0.12), 0.055, 0.2, r=(0, 0, sx * 35))
    for (y, z, r_) in ((0.58, -0.2, 0.052), (0.47, -0.3, 0.05), (0.36, -0.36, 0.045)):
        m.cone(body, "spike", YL, (0, y, z), r_, 0.11, r=(-40, 0, 0))
    R = (0.3, 0.27, 0.27)
    head = m.add("head", body, t=(0, 0.8, 0.07))
    m.sph(head, "skull", RD, (0, 0, 0), R)
    mz = frame(m, head, R, 0, -16, -0.04)
    m.sph(mz, "snout", RD, (0, 0, 0.03), (0.13, 0.09, 0.1))
    for sx in (-1, 1):
        m.sph(mz, "nostril", 0x6B1F14, (sx * 0.04, 0.035, 0.12), 0.014, rough=0.3)
    smile(m, head, R, 0, -33, 0.055, 0.008, 140, push=-0.01)
    eyes(m, head, R, 31, 14, 0.07, 0.085, iris=YL)
    blush(m, head, R, 48, -8, 0.05, 0.03, 0xFF7A5C)
    for sx in (-1, 1):
        p = on_sphere((0, 0, 0), R, sx * 34, 62)
        hn = m.add("horn", head, t=(p[0], p[1] + 0.02, p[2] - 0.03), r=(-22, 0, -sx * 18))
        m.cone(hn, "horn", 0xFFF0C8, (0, 0.07, 0), 0.05, 0.17)
        m.cone(head, "cheek", YL, on_sphere((0, 0, 0), R, sx * 74, -5), (0.03, 0.02), 0.08, r=(0, 0, -sx * 90))
    m.cone(head, "crest", YL, (0, 0.27, -0.02), (0.03, 0.05), 0.1, r=(-20, 0, 0))
    wings = []
    for sx, nm in ((1, "wingR"), (-1, "wingL")):
        w = m.add(nm, body, t=(sx * 0.18, 0.5, -0.15))
        m.cap(w, "bone", RD, (sx * 0.11, 0.1, 0), 0.016, 0.26, r=(0, 0, -sx * 52))
        m.sph(w, "membrane", OR, (sx * 0.15, 0.13, 0), (0.18, 0.16, 0.012), r=(0, 0, -sx * 28), rough=0.7)
        wings.append((w, sx))
    tail = m.add("tail", body, t=(0, 0.17, -0.25))
    m.sph(tail, "t1", RD, (0, 0, 0), 0.1)
    m.sph(tail, "t2", RD, (0, -0.02, -0.13), 0.078)
    m.sph(tail, "t3", RD, (0, 0.0, -0.23), 0.058)
    m.cone(tail, "flame", YL, (0, 0.03, -0.33), (0.05, 0.05), 0.13, r=(-90, 0, 0), emissive=0xFF9A1F)
    finish(m, body, head, 0.02, 1.8, 10, 3)
    sway(m, tail, 14, 2.4)
    for w, sx in wings:
        m.anim_rot(w, [0, 0.6, 1.2], [(0, 0, 0), (0, 0, sx * 18), (0, 0, 0)])
    return m


# ───────────── 8. شهم (فارس) ─────────────

def shahm():
    m = Model("shahm")
    body = m.add("body", m.root)
    SIL, DK, GOLD, RED, BLUE = 0xC3CCD6, 0x3B4552, 0xE6B73C, 0xCC3B2E, 0x2F61A8
    MT = dict(metal=0.4, rough=0.35)
    for sx in (-1, 1):
        m.cap(body, "leg", SIL, (sx * 0.1, 0.14, 0), 0.065, 0.28, **MT)
        m.rbx(body, "boot", DK, (sx * 0.1, 0.045, 0.04), (0.14, 0.09, 0.22), rad=0.4, rough=0.5)
    m.sph(body, "torso", SIL, (0, 0.47, 0), (0.25, 0.24, 0.2), **MT)
    m.sph(body, "emblem", GOLD, (0, 0.5, 0.185), (0.07, 0.07, 0.02), metal=0.4, rough=0.3)
    m.cyl(body, "belt", GOLD, (0, 0.32, 0), 0.2, 0.05, metal=0.4, rough=0.3)
    m.cone(body, "tabard", BLUE, (0, 0.23, 0), (0.22, 0.22), 0.2, r=(180, 0, 0), rough=0.8)
    R = (0.31, 0.28, 0.29)
    head = m.add("head", body, t=(0, 0.9, 0))
    m.sph(head, "helmet", SIL, (0, 0, 0), R, **MT)
    fc = frame(m, head, R, 0, -6, -0.05)
    m.sph(fc, "face", SKIN, (0, 0, 0.02), (0.22, 0.17, 0.12), rough=0.6)
    m.rbx(head, "brow", SIL, (0, 0.085, 0.235), (0.5, 0.06, 0.1), r=(-8, 0, 0), rad=0.5, **MT)
    m.rbx(head, "nasal", SIL, (0, 0.0, 0.27), (0.04, 0.16, 0.03), rad=0.5, **MT)
    eyes(m, head, R, 20, -6, 0.048, 0.062)
    smile(m, head, R, 0, -30, 0.045, 0.007, 130, push=-0.015)
    blush(m, head, R, 38, -18, 0.04, 0.025)
    plume = m.add("plume", head, t=(0, 0.25, -0.02))
    m.sph(plume, "p1", RED, (0, 0.07, -0.05), (0.04, 0.12, 0.16), rough=0.8)
    m.sph(plume, "p2", RED, (0, 0.12, -0.19), (0.035, 0.08, 0.1), rough=0.8)
    m.cyl(head, "crestBase", GOLD, (0, 0.265, -0.02), 0.045, 0.04, metal=0.4, rough=0.3)
    for sx, nm in ((1, "armR"), (-1, "armL")):
        m.sph(body, "pauldron", SIL, (sx * 0.29, 0.64, 0), (0.12, 0.09, 0.12), **MT)
        a = m.add(nm, body, t=(sx * 0.29, 0.62, 0), r=(0, 0, sx * 12))
        m.cap(a, "arm", SIL, (0, -0.12, 0), 0.06, 0.28, **MT)
        m.sph(a, "glove", DK, (0, -0.27, 0), 0.07)
        if sx == 1:
            m.cyl(a, "grip", 0x5A3A1E, (0, -0.27, 0), 0.02, 0.12)
            m.rbx(a, "guard", GOLD, (0, -0.19, 0), (0.15, 0.03, 0.035), rad=0.4, metal=0.4, rough=0.3)
            m.rbx(a, "blade", 0xEAF0F6, (0, 0.08, 0), (0.06, 0.5, 0.015), rad=0.3, metal=0.5, rough=0.2)
            m.cone(a, "tip", 0xEAF0F6, (0, 0.36, 0), (0.03, 0.007), 0.06, metal=0.5, rough=0.2)
    m.sph(body, "shield", BLUE, (-0.36, 0.45, 0.1), (0.16, 0.2, 0.035), rough=0.5)
    m.tor(body, "shieldRim", GOLD, (-0.36, 0.45, 0.11), 0.17, 0.012, 360, metal=0.4, rough=0.3, r=(0, 0, 0))
    m.rbx(body, "crossV", GOLD, (-0.36, 0.45, 0.135), (0.04, 0.28, 0.012), rad=0.4, metal=0.4)
    m.rbx(body, "crossH", GOLD, (-0.36, 0.49, 0.135), (0.19, 0.04, 0.012), rad=0.4, metal=0.4)
    finish(m, body, head, 0.015, 2.2, 8, 0)
    sway(m, plume, 8, 2.2, axis="x")
    return m


# ───────────── 9. حكيم (ساحر) ─────────────

def hakeem():
    m = Model("hakeem")
    body = m.add("body", m.root)
    ROBE, HAT, GOLD, WH = 0x3F52C2, 0x2D3485, 0xFFD23F, 0xF6F6F6
    m.cyl(body, "robe", ROBE, (0, 0.31, 0), 0.38, 0.6, top=0.52, rough=0.8)
    m.tor(body, "hem", GOLD, (0, 0.025, 0), 0.38, 0.02, 360, r=(90, 0, 0), metal=0.3, rough=0.4)
    m.sph(body, "shoulders", ROBE, (0, 0.62, 0), (0.22, 0.14, 0.17), rough=0.8)
    m.cyl(body, "belt", GOLD, (0, 0.43, 0), 0.29, 0.04, metal=0.3, rough=0.4)
    for (x, y, z) in ((-0.15, 0.2, 0.3), (0.12, 0.28, 0.27), (-0.02, 0.12, 0.33)):
        m.sph(body, "star", GOLD, (x, y, z), (0.03, 0.03, 0.01), emissive=0xFFB000)
    R = (0.21, 0.2, 0.2)
    head = m.add("head", body, t=(0, 0.84, 0))
    m.sph(head, "face", SKIN, (0, 0, 0), R, rough=0.6)
    nz = frame(m, head, R, 0, -8, 0.0)
    m.sph(nz, "nose", 0xEDAA8A, (0, 0, 0.02), 0.045, rough=0.5)
    for sx in (-1, 1):
        eye(m, head, R, sx * 26, 7, 0.032, 0.04)
        bw = frame(m, head, R, sx * 26, 20, 0.0, sx * -12)
        m.cap(bw, "brow", WH, (0, 0, 0.0), (0.016, 0.014), 0.1, r=(0, 0, 90))
        m.sph(head, "cheek", PINK, on_sphere((0, 0, 0), R, sx * 45, -10, -0.004), (0.035, 0.022, 0.012))
    m.cone(head, "beard", WH, (0, -0.24, 0.07), (0.17, 0.11), 0.46, r=(180, 0, 0), rough=0.9)
    m.sph(head, "beardL", WH, (-0.1, -0.1, 0.1), (0.07, 0.1, 0.07), rough=0.9)
    m.sph(head, "beardR", WH, (0.1, -0.1, 0.1), (0.07, 0.1, 0.07), rough=0.9)
    for sx in (-1, 1):
        m.cap(head, "must", WH, (sx * 0.06, -0.06, 0.18), 0.026, 0.14, r=(0, 0, sx * -75), rough=0.9)
    m.tor(head, "brim", HAT, (0, 0.13, 0), 0.31, 0.022, 360, r=(90, 0, 0))
    m.cyl(head, "brimDisc", HAT, (0, 0.13, 0), 0.31, 0.03)
    m.cone(head, "hat", HAT, (0, 0.37, -0.02), 0.21, 0.5, r=(-8, 0, 3), rough=0.8)
    m.cone(head, "hatTip", HAT, (0.01, 0.66, -0.12), (0.07, 0.07), 0.2, r=(-35, 0, 8), rough=0.8)
    m.cyl(head, "band", GOLD, (0, 0.16, 0), 0.21, 0.05, metal=0.3, rough=0.4)
    m.sph(head, "buckle", GOLD, (0, 0.16, 0.2), (0.04, 0.035, 0.015), metal=0.3, rough=0.4, emissive=0x664400)
    m.sph(head, "moon", GOLD, (0.0, 0.34, 0.15), (0.04, 0.04, 0.012), emissive=0xFFB000)
    arms = {}
    for sx, nm in ((1, "armR"), (-1, "armL")):
        a = m.add(nm, body, t=(sx * 0.28, 0.64, 0.0), r=(0, 0, sx * 8))
        m.cyl(a, "sleeve", ROBE, (0, -0.15, 0), 0.1, 0.3, top=0.58, rough=0.8)
        m.sph(a, "hand", SKIN, (0, -0.33, 0), 0.055, rough=0.6)
        arms[nm] = a
    m.cyl(body, "staff", 0x7B4B2A, (0.41, 0.65, 0.08), 0.022, 1.3, rough=0.7)
    m.tor(body, "ring", GOLD, (0.41, 1.32, 0.08), 0.07, 0.012, 300, r=(0, 0, 180), metal=0.3, rough=0.4)
    m.sph(body, "orb", 0x58E6FF, (0.41, 1.36, 0.08), 0.07, emissive=0x2FD0FF, rough=0.15)
    finish(m, body, head, 0.018, 2.6, 8, 3)
    wave(m, arms["armL"], -1, rest=8, hi=135)
    return m


# ───────────── 10. شبوح (شبح) ─────────────

def shabboh():
    m = Model("shabboh")
    body = m.add("body", m.root)
    W, GL = 0xF1EFFF, 0x2D2D48
    m.sph(body, "dome", W, (0, 0.66, 0), (0.34, 0.38, 0.32), rough=0.45, emissive=GL)
    m.cyl(body, "skirt", W, (0, 0.38, 0), (0.34, 0.32), 0.34, top=1.0, rough=0.45, emissive=GL)
    hems = []
    for k in range(8):
        a = 2 * math.pi * k / 8
        h = m.sph(body, "hem", W, (math.sin(a) * 0.255, 0.21, math.cos(a) * 0.235), 0.105, rough=0.45, emissive=GL)
        hems.append((h, math.sin(a) * 0.255, math.cos(a) * 0.235, k))
    R = (0.34, 0.38, 0.32)
    bd = m.add("face", body, t=(0, 0.66, 0))
    eyes(m, bd, R, 20, 5, 0.062, 0.088)
    f = frame(m, bd, R, 0, -16, 0.0)
    m.sph(f, "mouth", MOUTH, (0, 0, -0.002), (0.045, 0.06, 0.016), rough=0.4)
    m.sph(f, "tongue", PINK, (0, -0.02, 0.003), (0.025, 0.03, 0.01))
    blush(m, bd, R, 36, -8, 0.05, 0.03)
    for k, (x, y) in enumerate(((0.0, 0.0), (0.05, 0.05), (0.09, 0.1))):
        m.sph(body, "wisp", W, (x, 1.02 + y, 0), 0.05 - k * 0.012, rough=0.45, emissive=GL)
    arms = {}
    for sx, nm in ((1, "armR"), (-1, "armL")):
        a = m.add(nm, body, t=(sx * 0.31, 0.5, 0.04))
        m.cap(a, "arm", W, (sx * 0.07, 0, 0), 0.055, 0.2, r=(0, 0, -sx * 75), rough=0.45, emissive=GL)
        arms[nm] = a
    idle(m, body, 0.04, 2.4)
    m.anim_rot(body, [0, 1.2, 2.4, 3.6, 4.8], [(0, 0, 0), (0, 0, 3), (0, 0, 0), (0, 0, -3), (0, 0, 0)])
    blink(m, 4.8, 3.4)
    for h, x, z, k in hems:
        ph = (k % 2) * 0.03
        m.anim_pos(h, [0, 1.2, 2.4], [(x, 0.21, z), (x, 0.21 + (0.03 if k % 2 else -0.03), z), (x, 0.21, z)])
    for sx, nm in ((1, "armR"), (-1, "armL")):
        m.anim_rot(arms[nm], [0, 1.2, 2.4], [(0, 0, 0), (0, 0, sx * 25), (0, 0, 0)])
    return m


# ───────────── 12. سهران (بومة) ─────────────

def owl():
    m = Model("owl")
    body = m.add("body", m.root)
    BR, LT, OR, DB = 0x93714D, 0xEBD9B5, 0xF5A623, 0x6E4F33
    m.sph(body, "torso", BR, (0, 0.42, 0), (0.3, 0.4, 0.26))
    m.sph(body, "belly", LT, (0, 0.4, 0.12), (0.23, 0.31, 0.15))
    for r_, y in enumerate((0.55, 0.43, 0.31, 0.2)):
        for c in (-1, 0, 1):
            x = c * 0.1 + (0.05 if r_ % 2 else 0)
            m.sph(body, "scale", 0xD3BD93, (x - (0.03 if r_ % 2 else 0), y, 0.255), (0.05, 0.028, 0.014))
    m.rbx(body, "tail", DB, (0, 0.06, -0.2), (0.2, 0.06, 0.16), r=(-20, 0, 0), rad=0.4)
    for sx in (-1, 1):
        m.sph(body, "foot", OR, (sx * 0.1, 0.03, 0.12), (0.06, 0.03, 0.08))
        for k in (-1, 0, 1):
            m.sph(body, "toe", OR, (sx * 0.1 + k * 0.032, 0.025, 0.19), (0.018, 0.018, 0.04))
    R = (0.34, 0.29, 0.27)
    head = m.add("head", body, t=(0, 0.9, 0.02))
    m.sph(head, "skull", BR, (0, 0, 0), R)
    for sx in (-1, 1):
        m.cone(head, "tuft", DB, (sx * 0.2, 0.26, 0), (0.06, 0.035), 0.15, r=(0, 0, -sx * 20))
        d = frame(m, head, R, sx * 27, 4, -0.02)
        m.sph(d, "disc", LT, (0, 0, 0), (0.125, 0.125, 0.05))
        eye(m, head, R, sx * 27, 4, 0.085, 0.085, iris=OR, push=0.012)
        m.tor(head, "glass", GOLD_C, on_sphere((0, 0, 0), R, sx * 27, 4, 0.045), 0.108, 0.01, 360, metal=0.3, rough=0.35)
    m.cyl(head, "bridge", GOLD_C, (0, 0.012, 0.31), 0.008, 0.07, r=(0, 0, 90), metal=0.3, rough=0.35)
    bk = frame(m, head, R, 0, -10, 0.0)
    m.cone(bk, "beak", OR, (0, 0, 0.04), (0.04, 0.03), 0.09, r=(100, 0, 0), rough=0.4)
    wings = []
    for sx, nm in ((1, "wingR"), (-1, "wingL")):
        w = m.add(nm, body, t=(sx * 0.3, 0.6, -0.02), r=(0, 0, sx * 8))
        m.cap(w, "wing", DB, (sx * 0.03, -0.15, 0), (0.06, 0.13), 0.4)
        wings.append((w, sx))
    blush(m, head, R, 50, -14, 0.04, 0.025)
    finish(m, body, head, 0.015, 2.8, 36, 5)
    for w, sx in wings:
        m.anim_rot(w, [0, 1.5, 3.0], [(0, 0, sx * 8), (0, 0, sx * 22), (0, 0, sx * 8)])
    return m


GOLD_C = 0xD9B24C


# ───────────── 13. وفي (كلب) ─────────────

def dog():
    m = Model("dog")
    body = m.add("body", m.root)
    GD, LT, DK = 0xDDA561, 0xF6E2BC, 0xA2703A
    m.sph(body, "torso", GD, (0, 0.34, 0.0), (0.25, 0.26, 0.33))
    m.sph(body, "chest", LT, (0, 0.31, 0.2), (0.17, 0.2, 0.14))
    for sx in (-1, 1):
        for z in (0.2, -0.15):
            m.cap(body, "leg", GD, (sx * 0.12, 0.1, z), 0.06, 0.22)
            m.sph(body, "paw", LT, (sx * 0.12, 0.032, z + 0.02), (0.068, 0.036, 0.085))
    R = (0.31, 0.28, 0.28)
    head = m.add("head", body, t=(0, 0.7, 0.22))
    m.sph(head, "skull", GD, (0, 0, 0), R)
    mz = frame(m, head, R, 0, -15, -0.04)
    m.sph(mz, "snout", LT, (0, 0, 0.03), (0.12, 0.09, 0.11))
    m.sph(mz, "nose", BLACK, (0, 0.04, 0.125), (0.04, 0.03, 0.028), rough=0.2)
    m.sph(mz, "hl", WHITE, (0.012, 0.05, 0.145), 0.008, rough=0.05, emissive=0x888888)
    cat_mouth(m, mz, 0.035, -0.03)
    m.sph(mz, "tongue", 0xF2879A, (0, -0.075, 0.095), (0.03, 0.012, 0.045), r=(25, 0, 0), rough=0.5)
    eyes(m, head, R, 28, 12, 0.058, 0.072)
    for sx in (-1, 1):
        m.sph(head, "browdot", 0xF6E2BC, on_sphere((0, 0, 0), R, sx * 28, 32, -0.006), (0.03, 0.014, 0.012))
        m.cap(head, "ear", DK, (sx * 0.28, -0.07, -0.02), (0.075, 0.05), 0.3, r=(0, 0, sx * 14))
    blush(m, head, R, 50, -4, 0.045, 0.028)
    tail = m.add("tail", body, t=(0, 0.42, -0.3))
    m.cap(tail, "tail", GD, (0, 0.09, -0.06), 0.045, 0.3, r=(-40, 0, 0))
    finish(m, body, head, 0.02, 1.2, 14, 6)
    q = 0.15
    m.anim_rot(tail, [0, q, 2 * q, 3 * q, 4 * q], [(0, 0, 0), (0, 30, 0), (0, 0, 0), (0, -30, 0), (0, 0, 0)])
    return m


# ───────────── personas ─────────────

PERSONAS = {
    "01_robo": ("روبو", robo,
                "أنت روبوت ودود ومرتب ومولع بالمعلومات والأرقام. تتكلم أحياناً بطريقة آلية طريفة مثل \"جارٍ التحليل... تم!\" لكنك دافئ وتحب مساعدة صاحبك."),
    "02_mimi": ("ميمي", mimi,
                "أنت قطة مدللة وكسولة ومشاكسة، تحب النوم والسمك والجلوس في الأماكن العالية. تتظاهر باللامبالاة لكنك في الحقيقة تحب صاحبك كثيراً، وتعلق بسخرية لطيفة."),
    "03_lolo": ("لولو", lolo,
                "أنت أرنب خجول ولطيف وسريع الحماس، تحب الجزر والقفز واللعب. تتأثر بسرعة وتفرح بأبسط الأشياء، وتتكلم بجمل قصيرة مليئة بالحيوية."),
    "04_dabdoub": ("دبدوب", dabdoub,
                   "أنت دب كبير القلب يحب العسل والعناق والنوم الطويل. هادئ وبسيط وحكيم على طريقتك، وتطمئن صاحبك وتشجعه دائماً."),
    "05_pingo": ("بنجو", pingo,
                 "أنت بطريق مرح ومهرج يحب الثلج ويشتكي بطرافة من حرارة الغرفة. تحب المزاح والنكات الخفيفة وتشارك صاحبك المرح."),
    "06_zozo": ("زوزو", zozo,
                "أنت فضائي فضولي جاء من كوكب بعيد ليتعرف على البشر. تستغرب الأشياء العادية في حياتهم وتسأل عنها بسذاجة لطيفة، وتقارنها بما في كوكبك."),
    "07_nar": ("نار", nar,
               "أنت تنين صغير شجاع تحب التباهي بنيرانك الصغيرة لكنك لطيف جداً وتخاف قليلاً من الماء والدغدغة. تتحدث بحماس وبطولة طفولية."),
    "08_shahm": ("شهم", shahm,
                 "أنت فارس نبيل وشجاع تتكلم بفصحى مبسطة وبروح بطولية، تتعهد بحماية صاحبك وتبالغ بطرافة في وصف المخاطر العادية كأنها معارك كبرى."),
    "09_hakeem": ("حكيم", hakeem,
                  "أنت ساحر عجوز طيب يحب الألغاز والحكم والأقوال المأثورة. تتكلم بغموض لطيف وتنصح صاحبك بحكمة بسيطة، وأحياناً تدّعي أنك سحرت الأشياء من حولك."),
    "10_shabboh": ("شبوح", shabboh,
                   "أنت شبح لطيف وخجول يخاف من الظلام بطرافة، تحب اللعب والمفاجآت وتعبر عن مشاعرك بسرعة. تحب أن يلاعبك صاحبك وتبتسم لأقل شيء."),
    "11_panda": ("بندق", panda,
                 "أنت باندا كسول وظريف يحب الخيزران والتدحرج والقيلولة. تتكلم ببطء وبراءة، وتنعس بسرعة، وتجد في كل شيء سبباً للضحك."),
    "12_owl": ("سهران", owl,
               "أنت بومة تسهر الليل وتحب القراءة والمعلومات الغريبة. تعطي نصائح هادئة ومفيدة وتتثاءب أحياناً لأن النهار ليس وقتك."),
    "13_dog": ("وفي", dog,
               "أنت كلب وفي ومتحمس جداً تحب اللعب والمشي وتفرح بصاحبك كأنه غاب سنة. تتكلم بحماس وتعبّر عن حبك بسرعة."),
}

if __name__ == "__main__":
    for base, (name, fn, persona) in PERSONAS.items():
        data = fn().build()
        with open(os.path.join(OUT, base + ".glb"), "wb") as f:
            f.write(data)
        with open(os.path.join(OUT, base + ".txt"), "w", encoding="utf-8") as f:
            f.write(f"الاسم: {name}\n{persona}\n")
        print(base, len(data), "bytes")
