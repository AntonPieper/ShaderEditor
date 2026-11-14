#!/usr/bin/env python3
"""Draw the sky of the Skybox example as a cubemap strip.

Writes app/src/debug/assets/examples/textures/sky.png: six square faces
in one row, +X, -X, +Y, -Y, +Z, -Z, as ShaderEditor reads a samplerCube
(SPEC 4). The directions are the world's of rotationMatrix: x east,
y north, z up, so +Z is the zenith and -Z the ground.

A day sky, darker towards the zenith; the sun low in the east; a ridge
of hills all around whose height changes with the bearing, so turning
shows it; a red post in the north. Only the Python standard library
is used. Run it from the repository root: python3 tools/examples/sky.py
"""
import math
import struct
import zlib

SIZE = 256
OUT = "app/src/debug/assets/examples/textures/sky.png"


def direction(face, s, t):
    """The direction of texel (s, t) of face, as GL defines cube faces;
    s and t run from -1 to 1, left to right and top to bottom."""
    return [
        (1.0, -t, -s),
        (-1.0, -t, s),
        (s, 1.0, t),
        (s, -1.0, -t),
        (s, -t, 1.0),
        (-s, -t, -1.0),
    ][face]


def mix(a, b, t):
    return tuple(x + (y - x) * t for x, y in zip(a, b))


def smoothstep(e0, e1, x):
    t = min(max((x - e0) / (e1 - e0), 0.0), 1.0)
    return t * t * (3.0 - 2.0 * t)


SUN = (0.92, 0.0, 0.38)


def sky(x, y, z):
    n = math.sqrt(x * x + y * y + z * z)
    x, y, z = x / n, y / n, z / n
    bearing = math.atan2(x, y)  # 0 north, pi / 2 east
    # The air: pale at the horizon, deep blue overhead.
    color = mix((0.78, 0.86, 0.95), (0.18, 0.38, 0.75), math.sqrt(max(z, 0.0)))
    # The sun and its glow.
    sun = math.sqrt(sum((a - b) ** 2 for a, b in zip((x, y, z), normalize(SUN))))
    color = mix(color, (1.0, 0.85, 0.6), 0.6 * math.exp(-6.0 * sun))
    color = mix(color, (1.0, 0.98, 0.9), smoothstep(0.07, 0.06, sun))
    # Hills: their height above the horizon depends on the bearing.
    ridge = 0.06 + 0.035 * math.sin(3.0 * bearing) + 0.02 * math.sin(7.0 * bearing + 1.0)
    hills = mix((0.36, 0.45, 0.55), (0.22, 0.3, 0.38), smoothstep(ridge, -0.1, z))
    color = mix(color, hills, smoothstep(ridge + 0.006, ridge - 0.006, z))
    # The ground below the horizon.
    ground = mix((0.2, 0.27, 0.18), (0.1, 0.14, 0.09), min(-z * 3.0, 1.0))
    color = mix(color, ground, smoothstep(0.006, -0.006, z))
    # A red post in the north.
    if abs(bearing) < 0.02 and -0.02 < z < ridge + 0.06:
        color = (0.85, 0.2, 0.15)
    return color


def normalize(v):
    n = math.sqrt(sum(c * c for c in v))
    return tuple(c / n for c in v)


def main():
    rows = []
    above = bytes(6 * SIZE * 3)
    for row in range(SIZE):
        t = (row + 0.5) / SIZE * 2.0 - 1.0
        line = bytearray()
        for face in range(6):
            for col in range(SIZE):
                s = (col + 0.5) / SIZE * 2.0 - 1.0
                r, g, b = sky(*direction(face, s, t))
                line += bytes(round(min(max(c, 0.0), 1.0) * 255) for c in (r, g, b))
        # PNG filter "Up": each byte minus the one above it, which
        # leaves little but zeros in smooth gradients.
        rows.append(b"\x02" + bytes((a - b) & 0xff for a, b in zip(line, above)))
        above = bytes(line)
    raw = b"".join(rows)

    def chunk(kind, data):
        return (struct.pack(">I", len(data)) + kind + data +
                struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff))

    png = (b"\x89PNG\r\n\x1a\n" +
           chunk(b"IHDR", struct.pack(">IIBBBBB", 6 * SIZE, SIZE, 8, 2, 0, 0, 0)) +
           chunk(b"IDAT", zlib.compress(raw, 9)) +
           chunk(b"IEND", b""))
    with open(OUT, "wb") as f:
        f.write(png)


if __name__ == "__main__":
    main()
