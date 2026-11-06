import json, math, struct
import numpy as np


def lin(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def hexcolor(h):
    r, g, b = (h >> 16) & 255, (h >> 8) & 255, h & 255
    return [lin(r / 255), lin(g / 255), lin(b / 255)]


# ───────────── primitives (unit size, centered, outward normals) ─────────────

def _fix_winding(pos, nrm, idx):
    idx = idx.reshape(-1, 3).copy()
    p0, p1, p2 = pos[idx[:, 0]], pos[idx[:, 1]], pos[idx[:, 2]]
    face_n = np.cross(p1 - p0, p2 - p0)
    avg_n = nrm[idx[:, 0]] + nrm[idx[:, 1]] + nrm[idx[:, 2]]
    flip = (face_n * avg_n).sum(axis=1) < 0
    idx[flip] = idx[flip][:, [0, 2, 1]]
    area = np.linalg.norm(face_n, axis=1)
    idx = idx[area > 1e-9]
    return idx.reshape(-1)


def sphere(seg=44, rings=30):
    pos, idx = [], []
    for i in range(rings + 1):
        th = math.pi * i / rings
        for j in range(seg + 1):
            ph = 2 * math.pi * j / seg
            pos.append((math.sin(th) * math.cos(ph), math.cos(th), math.sin(th) * math.sin(ph)))
    for i in range(rings):
        for j in range(seg):
            a = i * (seg + 1) + j
            b = a + seg + 1
            idx += [a, b, a + 1, a + 1, b, b + 1]
    pos = np.array(pos, np.float32)
    return pos, pos.copy(), np.array(idx, np.uint32)


def box():
    faces = [((1, 0, 0), (0, 1, 0), (0, 0, 1)), ((-1, 0, 0), (0, 1, 0), (0, 0, -1)),
             ((0, 1, 0), (0, 0, 1), (1, 0, 0)), ((0, -1, 0), (0, 0, 1), (-1, 0, 0)),
             ((0, 0, 1), (1, 0, 0), (0, 1, 0)), ((0, 0, -1), (-1, 0, 0), (0, 1, 0))]
    pos, nrm, idx = [], [], []
    for n, u, v in faces:
        n, u, v = map(np.array, (n, u, v))
        base = len(pos)
        for su, sv in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
            pos.append(0.5 * n + 0.5 * su * u + 0.5 * sv * v)
            nrm.append(n)
        idx += [base, base + 1, base + 2, base, base + 2, base + 3]
    pos = np.array(pos, np.float32)
    nrm = np.array(nrm, np.float32)
    return pos, nrm, _fix_winding(pos, nrm, np.array(idx, np.uint32))


def frustum(top=1.0, seg=40):
    """bottom radius 1 at y=-0.5, top radius `top` at y=+0.5 (top=0 gives a cone)."""
    pos, nrm, idx = [], [], []
    slope = np.array([1.0, 1.0 - top, 1.0])
    for j in range(seg + 1):
        ph = 2 * math.pi * j / seg
        c, s = math.cos(ph), math.sin(ph)
        n = np.array([c, 1.0 - top, s])
        n = n / np.linalg.norm(n)
        pos.append((c, -0.5, s)); nrm.append(n)
        pos.append((c * top, 0.5, s * top)); nrm.append(n)
    for j in range(seg):
        a = 2 * j
        idx += [a, a + 1, a + 2, a + 2, a + 1, a + 3]
    # bottom cap
    cb = len(pos)
    pos.append((0, -0.5, 0)); nrm.append((0, -1, 0))
    for j in range(seg + 1):
        ph = 2 * math.pi * j / seg
        pos.append((math.cos(ph), -0.5, math.sin(ph))); nrm.append((0, -1, 0))
    for j in range(seg):
        idx += [cb, cb + 1 + j, cb + 2 + j]
    if top > 0:
        ct = len(pos)
        pos.append((0, 0.5, 0)); nrm.append((0, 1, 0))
        for j in range(seg + 1):
            ph = 2 * math.pi * j / seg
            pos.append((math.cos(ph) * top, 0.5, math.sin(ph) * top)); nrm.append((0, 1, 0))
        for j in range(seg):
            idx += [ct, ct + 1 + j, ct + 2 + j]
    pos = np.array(pos, np.float32)
    nrm = np.array(nrm, np.float32)
    return pos, nrm, _fix_winding(pos, nrm, np.array(idx, np.uint32))



def rbox(r=0.18, k=6):
    """Unit rounded box (size 1, centered). r = corner radius."""
    r = float(r)
    coords = [-0.5 + r * i / k for i in range(k + 1)] + [0.5 - r * (k - i) / k for i in range(k + 1)]
    n_ = len(coords)
    pos, nrm, idx = [], [], []
    for axis in range(3):
        for sign in (-0.5, 0.5):
            base = len(pos)
            a1, a2 = [i for i in range(3) if i != axis]
            for u in coords:
                for v in coords:
                    p = np.zeros(3)
                    p[axis], p[a1], p[a2] = sign, u, v
                    inner = np.clip(p, -0.5 + r, 0.5 - r)
                    d = p - inner
                    nn = d / np.linalg.norm(d) if np.linalg.norm(d) > 1e-9 else np.eye(3)[axis] * np.sign(sign)
                    pos.append(inner + nn * r)
                    nrm.append(nn)
            for i in range(n_ - 1):
                for j in range(n_ - 1):
                    a = base + i * n_ + j
                    idx += [a, a + 1, a + n_, a + 1, a + n_ + 1, a + n_]
    pos = np.array(pos, np.float32)
    nrm = np.array(nrm, np.float32)
    return pos, nrm, _fix_winding(pos, nrm, np.array(idx, np.uint32))


def capsule(hh=0.5, seg=36, rings=14):
    """Capsule along Y: radius 1, cylinder half-height hh, hemispherical caps."""
    hh = float(hh)
    pos, nrm, idx = [], [], []
    rows = []
    for i in range(rings + 1):            # top hemisphere
        th = (math.pi / 2) * i / rings
        rows.append((math.sin(th), math.cos(th), hh))
    for i in range(rings + 1):            # bottom hemisphere
        th = math.pi / 2 + (math.pi / 2) * i / rings
        rows.append((math.sin(th), math.cos(th), -hh))
    for (rr, ny, off) in rows:
        for j in range(seg + 1):
            ph = 2 * math.pi * j / seg
            c, s_ = math.cos(ph), math.sin(ph)
            pos.append((rr * c, ny + off, rr * s_))
            nrm.append((rr * c, ny, rr * s_))
    for i in range(len(rows) - 1):
        for j in range(seg):
            a = i * (seg + 1) + j
            b = a + seg + 1
            idx += [a, b, a + 1, a + 1, b, b + 1]
    pos = np.array(pos, np.float32)
    nrm = np.array(nrm, np.float32)
    return pos, nrm, _fix_winding(pos, nrm, np.array(idx, np.uint32))


def torus(ratio=0.08, arc=360.0, seg=48, tube=14):
    """Ring in the XY plane (axis +Z), major radius 1, minor radius `ratio`.
    The arc is centered at the bottom (-Y): arc=120 gives a smile."""
    ratio = float(ratio)
    arc_r = math.radians(float(arc))
    pos, nrm, idx = [], [], []
    full = arc >= 359.9
    for i in range(seg + 1):
        phi = -arc_r / 2 + arc_r * i / seg
        cx, cy = math.sin(phi), -math.cos(phi)
        for j in range(tube + 1):
            th = 2 * math.pi * j / tube
            nx = math.cos(th) * cx
            ny = math.cos(th) * cy
            nz = math.sin(th)
            pos.append((cx + ratio * nx, cy + ratio * ny, ratio * nz))
            nrm.append((nx, ny, nz))
    for i in range(seg):
        for j in range(tube):
            a = i * (tube + 1) + j
            b = a + tube + 1
            idx += [a, b, a + 1, a + 1, b, b + 1]
    pos = np.array(pos, np.float32)
    nrm = np.array(nrm, np.float32)
    return pos, nrm, _fix_winding(pos, nrm, np.array(idx, np.uint32))


def ao_colors(nrm):
    """Cheap baked shading: undersides slightly darker (vertex colour multiplies base colour)."""
    y = np.clip(nrm[:, 1], -1, 1)
    t = np.clip((y + 1.0) / 1.3, 0, 1)
    f = 0.74 + 0.26 * (t * t * (3 - 2 * t))
    return np.stack([f, f, f, np.ones_like(f)], axis=1).astype(np.float32)  # RGBA: أضمن توافقاً مع Filament


def make_geom(shape, param):
    if shape == "sphere":
        return sphere()
    if shape == "box":
        return box()
    if shape == "frustum":
        return frustum(param)
    if shape == "rbox":
        return rbox(param)
    if shape == "capsule":
        return capsule(param)
    if shape == "torus":
        return torus(param[0], param[1])
    raise ValueError(shape)


# ───────────── math helpers ─────────────

def quat_axis(axis, deg):
    h = math.radians(deg) / 2
    s = math.sin(h)
    x, y, z = axis
    return [x * s, y * s, z * s, math.cos(h)]


def qmul(a, b):
    ax, ay, az, aw = a
    bx, by, bz, bw = b
    return [aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz]


def euler_q(rx=0, ry=0, rz=0):
    """R = Ry * Rx * Rz (degrees)."""
    q = quat_axis((0, 1, 0), ry)
    q = qmul(q, quat_axis((1, 0, 0), rx))
    q = qmul(q, quat_axis((0, 0, 1), rz))
    return q


def on_sphere(center, radii, az, el, push=0.0):
    """Point on an ellipsoid surface. az=0 faces +Z (positive toward +X), el up."""
    a, e = math.radians(az), math.radians(el)
    d = (math.cos(e) * math.sin(a), math.sin(e), math.cos(e) * math.cos(a))
    r = radii if isinstance(radii, (tuple, list)) else (radii,) * 3
    return (center[0] + d[0] * (r[0] + push), center[1] + d[1] * (r[1] + push),
            center[2] + d[2] * (r[2] + push))


# خيارات التوليد (تُستخدم لإنتاج صيغ مبسّطة احتياطية)
OPTIONS = {"colors": True, "anim": True}

# ───────────── scene builder ─────────────

class Model:
    def __init__(self, name):
        self.name = name
        self.nodes = []
        self.meshes = []
        self.materials = []
        self.accessors = []
        self.views = []
        self.bin = bytearray()
        self._mat_cache = {}
        self._mesh_cache = {}
        self._geom_cache = {}
        self.channels = []   # (node, path, times, values)
        self.eyes = []
        self.root = self.add("root")

    # buffer helpers
    def _view(self, data: bytes, target=None):
        while len(self.bin) % 4:
            self.bin.append(0)
        off = len(self.bin)
        self.bin += data
        v = {"buffer": 0, "byteOffset": off, "byteLength": len(data)}
        if target:
            v["target"] = target
        self.views.append(v)
        return len(self.views) - 1

    def _acc(self, arr, ctype, atype, target=None, minmax=False):
        arr = np.ascontiguousarray(arr)
        vi = self._view(arr.tobytes(), target)
        a = {"bufferView": vi, "componentType": ctype, "count": int(len(arr)), "type": atype}
        if minmax:
            if arr.ndim == 1:
                a["min"], a["max"] = [float(arr.min())], [float(arr.max())]
            else:
                a["min"] = [float(x) for x in arr.min(axis=0)]
                a["max"] = [float(x) for x in arr.max(axis=0)]
        self.accessors.append(a)
        return len(self.accessors) - 1

    def mat(self, color, rough=0.65, metal=0.0, emissive=None):
        key = (color, rough, metal, emissive)
        if key in self._mat_cache:
            return self._mat_cache[key]
        m = {"name": f"m{len(self.materials)}",
             "pbrMetallicRoughness": {"baseColorFactor": hexcolor(color) + [1.0],
                                      "metallicFactor": metal, "roughnessFactor": rough}}
        if emissive is not None:
            m["emissiveFactor"] = hexcolor(emissive)
        self.materials.append(m)
        self._mat_cache[key] = len(self.materials) - 1
        return self._mat_cache[key]

    def mesh(self, shape, param, matidx):
        key = (shape, param, matidx)
        if key in self._mesh_cache:
            return self._mesh_cache[key]
        gk = (shape, param)
        if gk not in self._geom_cache:
            p, n, i = make_geom(shape, param)
            pa = self._acc(p, 5126, "VEC3", 34962, minmax=True)
            na = self._acc(n, 5126, "VEC3", 34962)
            ia = self._acc(i.astype(np.uint16), 5123, "SCALAR", 34963)
            ca = self._acc(ao_colors(n), 5126, "VEC4", 34962) if OPTIONS["colors"] else None
            self._geom_cache[gk] = (pa, na, ia, ca)
        pa, na, ia, ca = self._geom_cache[gk]
        attrs = {"POSITION": pa, "NORMAL": na}
        if ca is not None:
            attrs["COLOR_0"] = ca
        self.meshes.append({"name": f"{shape}{len(self.meshes)}",
                            "primitives": [{"attributes": attrs,
                                            "indices": ia, "material": matidx, "mode": 4}]})
        self._mesh_cache[key] = len(self.meshes) - 1
        return self._mesh_cache[key]

    def add(self, name, parent=None, t=(0, 0, 0), r=(0, 0, 0), s=(1, 1, 1), mesh=None):
        node = {"name": name}
        if any(abs(x) > 1e-9 for x in t):
            node["translation"] = [float(x) for x in t]
        if any(abs(x) > 1e-9 for x in r):
            node["rotation"] = [float(x) for x in euler_q(*r)]
        if any(abs(x - 1) > 1e-9 for x in s):
            node["scale"] = [float(x) for x in s]
        if mesh is not None:
            node["mesh"] = mesh
        self.nodes.append(node)
        idx = len(self.nodes) - 1
        if parent is not None:
            self.nodes[parent].setdefault("children", []).append(idx)
        return idx

    # shape helpers: all take parent node index
    def _shape(self, shape, param, parent, name, color, t, r, s, rough, metal, emissive):
        mi = self.mesh(shape, param, self.mat(color, rough, metal, emissive))
        return self.add(name, parent, t, r, s, mi)

    def sph(self, parent, name, color, t, rad, r=(0, 0, 0), rough=0.65, metal=0.0, emissive=None):
        s = rad if isinstance(rad, (tuple, list)) else (rad,) * 3
        return self._shape("sphere", None, parent, name, color, t, r, s, rough, metal, emissive)

    def box(self, parent, name, color, t, size, r=(0, 0, 0), rough=0.65, metal=0.0, emissive=None):
        return self._shape("box", None, parent, name, color, t, r, size, rough, metal, emissive)

    def cyl(self, parent, name, color, t, rad, h, r=(0, 0, 0), top=1.0, rough=0.65, metal=0.0, emissive=None):
        rx, rz = (rad, rad) if not isinstance(rad, (tuple, list)) else rad
        return self._shape("frustum", float(top), parent, name, color, t, r, (rx, h, rz), rough, metal, emissive)

    def rbx(self, parent, name, color, t, size, r=(0, 0, 0), rad=0.2, rough=0.5, metal=0.0, emissive=None):
        return self._shape("rbox", round(float(rad), 3), parent, name, color, t, r, size, rough, metal, emissive)

    def cap(self, parent, name, color, t, rad, length, r=(0, 0, 0), rough=0.65, metal=0.0, emissive=None):
        """Capsule of radius rad and total length `length` along local Y (rad may be (rx,rz) for flattening)."""
        rx, rz = (rad, rad) if not isinstance(rad, (tuple, list)) else rad
        hh = max(0.0, (length / rx - 2.0) / 2.0)
        return self._shape("capsule", round(hh, 3), parent, name, color, t, r, (rx, rx, rz), rough, metal, emissive)

    def tor(self, parent, name, color, t, rad, tube, arc=360.0, r=(0, 0, 0), rough=0.5, metal=0.0, emissive=None):
        return self._shape("torus", (round(tube / rad, 4), float(arc)), parent, name, color, t, r,
                           (rad, rad, rad), rough, metal, emissive)

    def cone(self, parent, name, color, t, rad, h, r=(0, 0, 0), rough=0.65, metal=0.0, emissive=None):
        rx, rz = (rad, rad) if not isinstance(rad, (tuple, list)) else rad
        return self._shape("frustum", 0.0, parent, name, color, t, r, (rx, h, rz), rough, metal, emissive)

    # animation: values are positions (vec3) for translation, or euler tuples (rx,ry,rz) for rotation
    def anim_rot(self, node, times, eulers):
        self.channels.append((node, "rotation", times, [euler_q(*e) for e in eulers]))

    def anim_scale(self, node, times, scales):
        self.channels.append((node, "scale", times, [list(v) for v in scales]))

    def anim_pos(self, node, times, positions):
        self.channels.append((node, "translation", times, [list(p) for p in positions]))

    def build(self):
        animations = []
        if self.channels and OPTIONS["anim"]:
            samplers, chans = [], []
            for node, path, times, vals in self.channels:
                ta = self._acc(np.array(times, np.float32), 5126, "SCALAR", None, minmax=True)
                va = self._acc(np.array(vals, np.float32), 5126, "VEC3" if path in ("translation", "scale") else "VEC4")
                samplers.append({"input": ta, "output": va, "interpolation": "LINEAR"})
                chans.append({"sampler": len(samplers) - 1, "target": {"node": node, "path": path}})
            animations.append({"name": "idle", "samplers": samplers, "channels": chans})
        gltf = {"asset": {"version": "2.0", "generator": "ARCharacters procedural generator"},
                "scene": 0, "scenes": [{"name": self.name, "nodes": [self.root]}],
                "nodes": self.nodes, "meshes": self.meshes, "materials": self.materials,
                "accessors": self.accessors, "bufferViews": self.views,
                "buffers": [{"byteLength": len(self.bin)}]}
        if animations:
            gltf["animations"] = animations
        js = json.dumps(gltf, separators=(",", ":")).encode()
        js += b" " * ((4 - len(js) % 4) % 4)
        binb = bytes(self.bin) + b"\x00" * ((4 - len(self.bin) % 4) % 4)
        total = 12 + 8 + len(js) + 8 + len(binb)
        return (struct.pack("<III", 0x46546C67, 2, total) +
                struct.pack("<II", len(js), 0x4E4F534A) + js +
                struct.pack("<II", len(binb), 0x004E4942) + binb)


def face_point(radii, az, el, push=0.0):
    """Position and orientation (euler deg) of a frame sitting on an ellipsoid, local +Z pointing outward."""
    return on_sphere((0, 0, 0), radii, az, el, push), (-el, az, 0)
