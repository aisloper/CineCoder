#!/usr/bin/env python3
"""Generates the 16x16 pixel-art textures for CineCorder (no external deps)."""
import os
import struct
import zlib

ROOT = os.path.join(os.path.dirname(__file__), "..",
                    "src/main/resources/assets/cinecorder/textures")


def write_png(path, pixels):
    """pixels: list of rows, each row a list of (r,g,b,a) tuples."""
    h = len(pixels)
    w = len(pixels[0])
    raw = b""
    for row in pixels:
        raw += b"\x00" + b"".join(struct.pack("BBBB", *px) for px in row)

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)


def blank(size=16, color=(0, 0, 0, 0)):
    return [[color for _ in range(size)] for _ in range(size)]


def rect(img, x0, y0, x1, y1, color):
    for y in range(max(0, y0), min(len(img), y1)):
        for x in range(max(0, x0), min(len(img[0]), x1)):
            img[y][x] = color


def noise(img, seed, amount=10):
    for y in range(len(img)):
        for x in range(len(img[0])):
            r, g, b, a = img[y][x]
            if a == 0:
                continue
            n = ((x * 73 + y * 151 + seed * 37) % (amount * 2 + 1)) - amount
            img[y][x] = (max(0, min(255, r + n)), max(0, min(255, g + n)),
                         max(0, min(255, b + n)), a)


def cassette(label, label_dark):
    img = blank()
    body = (38, 40, 46, 255)
    body_hi = (62, 65, 74, 255)
    reel = (24, 24, 28, 255)
    hub = (200, 200, 205, 255)
    # shell
    rect(img, 1, 2, 15, 14, body)
    rect(img, 1, 2, 15, 3, body_hi)
    rect(img, 2, 3, 14, 13, (52, 54, 62, 255))
    # label window
    rect(img, 4, 3, 12, 8, label)
    rect(img, 4, 3, 12, 4, label_dark)
    rect(img, 5, 5, 7, 7, (235, 235, 235, 200))
    rect(img, 9, 5, 11, 7, (235, 235, 235, 200))
    # reels
    rect(img, 5, 9, 7, 12, reel)
    rect(img, 9, 9, 11, 12, reel)
    rect(img, 5, 10, 7, 11, hub)
    rect(img, 9, 10, 11, 11, hub)
    # tape window
    rect(img, 6, 10, 10, 11, (70, 70, 78, 255))
    noise(img, 7, 6)
    return img


def animation_table_top():
    img = blank()
    wood = (128, 94, 58, 255)
    wood_d = (104, 75, 45, 255)
    rect(img, 0, 0, 16, 16, wood)
    noise(img, 3, 8)
    for x in range(0, 16, 5):
        rect(img, x, 0, x + 1, 16, wood_d)
    # film-strip inlay
    rect(img, 2, 2, 14, 14, (28, 29, 34, 255))
    rect(img, 2, 2, 14, 3, (70, 72, 82, 255))
    for x in range(2, 14, 2):
        rect(img, x, 3, x + 1, 4, (150, 150, 160, 255))
        rect(img, x, 12, x + 1, 13, (150, 150, 160, 255))
    # reel silhouette
    for y in range(5, 11):
        for x in range(5, 11):
            if abs(x - 7.5) + abs(y - 7.5) < 4:
                img[y][x] = (96, 176, 216, 255)
    rect(img, 7, 5, 9, 10, (230, 240, 250, 255))
    return img


def animation_table_side():
    img = blank()
    wood = (118, 86, 52, 255)
    wood_d = (92, 66, 40, 255)
    rect(img, 0, 0, 16, 16, wood)
    noise(img, 11, 8)
    for x in range(0, 16, 5):
        rect(img, x, 0, x + 1, 16, wood_d)
    # drawer with handle
    rect(img, 2, 4, 14, 13, (96, 70, 43, 255))
    rect(img, 2, 4, 14, 5, (72, 52, 32, 255))
    rect(img, 6, 8, 10, 9, (210, 180, 90, 255))
    return img


def projector_side():
    img = blank()
    metal = (56, 58, 66, 255)
    metal_l = (86, 89, 100, 255)
    dark = (24, 25, 30, 255)
    rect(img, 0, 0, 16, 16, metal)
    noise(img, 5, 6)
    rect(img, 0, 0, 16, 2, metal_l)
    rect(img, 2, 5, 14, 14, (44, 46, 54, 255))
    # vents
    for y in range(7, 13, 2):
        rect(img, 3, y, 13, y + 1, dark)
    return img


def projector_front():
    img = blank()
    metal = (56, 58, 66, 255)
    metal_l = (86, 89, 100, 255)
    rect(img, 0, 0, 16, 16, metal)
    noise(img, 9, 6)
    rect(img, 0, 0, 16, 2, metal_l)
    # lens
    rect(img, 4, 4, 12, 12, (20, 20, 24, 255))
    rect(img, 5, 5, 11, 11, (60, 120, 170, 255))
    rect(img, 6, 6, 10, 10, (150, 210, 250, 255))
    rect(img, 7, 7, 9, 9, (235, 250, 255, 255))
    # little red light
    rect(img, 13, 2, 15, 4, (220, 60, 50, 255))
    return img


ICON_ROOT = os.path.join(os.path.dirname(__file__), "..", "src/main/resources/assets/cinecorder")


def white():
    return [[(255, 255, 255, 255) for _ in range(16)] for _ in range(16)]


def icon():
    size = 64
    img = blank(size, (18, 20, 26, 255))
    for y in range(size):
        for x in range(size):
            d = max(abs(x - 31.5), abs(y - 31.5)) / 31.5
            r, g, b, a = img[y][x]
            k = 1 - 0.35 * d
            img[y][x] = (int(r * k), int(g * k), int(b * k), 255)
    rect(img, 6, 14, 58, 52, (0, 0, 0, 0))
    rect(img, 7, 15, 57, 51, (52, 56, 66, 255))
    rect(img, 8, 16, 56, 48, (74, 79, 92, 255))
    rect(img, 13, 18, 51, 32, (92, 170, 222, 255))
    rect(img, 15, 20, 49, 23, (140, 205, 240, 255))
    for cx in (22, 42):
        for y in range(34, 46):
            for x in range(cx - 6, cx + 6):
                if (x - cx) ** 2 + (y - 40) ** 2 < 30:
                    img[y][x] = (28, 30, 36, 255)
                if (x - cx) ** 2 + (y - 40) ** 2 < 8:
                    img[y][x] = (214, 216, 222, 255)
    rect(img, 10, 50, 54, 54, (255, 170, 80, 255))
    return img


def main():
    write_png(os.path.join(ROOT, "scene/white.png"), white())
    write_png(os.path.join(ICON_ROOT, "icon.png"), icon())
    write_png(os.path.join(ROOT, "item/blank_cassette.png"),
              cassette((208, 206, 198, 255), (176, 174, 166, 255)))
    write_png(os.path.join(ROOT, "item/animation_cassette.png"),
              cassette((90, 168, 220, 255), (52, 120, 176, 255)))
    write_png(os.path.join(ROOT, "block/animation_table_top.png"),
              animation_table_top())
    write_png(os.path.join(ROOT, "block/animation_table_side.png"),
              animation_table_side())
    write_png(os.path.join(ROOT, "block/projector_side.png"), projector_side())
    write_png(os.path.join(ROOT, "block/projector_front.png"),
              projector_front())
    print("textures written to", os.path.normpath(ROOT))


if __name__ == "__main__":
    main()
