#!/usr/bin/env python3
"""
Gera o logo do Cairnfire em pixel art: um cairn (pedras empilhadas) de noite, com a pedra do topo a arder.
A luz da chama ilumina as pedras, o fundo e o título.

Uso (precisa de Pillow; o .icns precisa do `iconutil`, que só existe no macOS):
    python3 assets/make_logo.py

Escreve em assets/: logo.png/svg, banner.png/svg, banner-ui.png (para a janela da app), icon.icns (macOS) e icon.ico (Windows).
"""
import math
import os
import shutil
import subprocess
import sys
import tempfile

from PIL import Image, ImageDraw

OUT = os.path.dirname(os.path.abspath(__file__))


def rgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))


# ---------- paleta ----------
BG = [rgb(c) for c in ("#14111b", "#22141f", "#3a1b1d", "#5c2a1c")]  # noite -> brilho quente
COOL = [rgb(c) for c in ("#2f2e3d", "#5a596f", "#8d8ba3", "#b9b7cc")]  # pedra: escuro, médio, claro, brilho
WARM = [rgb(c) for c in ("#4a3338", "#8d6e6c", "#d9a98a", "#ffdcb0")]  # a mesma pedra iluminada pelo fogo
FIRE = {"R": rgb("#d0391f"), "O": rgb("#ff8a1f"), "Y": rgb("#ffc933"), "W": rgb("#fff3b0")}
GROUND = rgb("#0b0910")
STAR, STAR_DIM = rgb("#cfd0e8"), rgb("#4a4963")

FLAME = [
    "....R.....",
    "...RR.....",
    "...RRR....",
    "..RRORR...",
    "..RROOR...",
    ".RROOOOR..",
    ".RROYOOR..",
    ".ROOYYOOR.",
    ".ROYYWYOR.",
    ".ROYWWYOR.",
    "..ROYYYR..",
    "...RRRR...",
]

# Pedras de cima para baixo: (x0, x1, y0, y1, chanfro) numa grelha 32x32.
STONES = [
    (13, 19, 13, 16, 2),
    (9, 20, 17, 20, 1),
    (7, 24, 21, 25, 2),
    (3, 28, 26, 30, 2),
]
FLAME_POS = (11, 1)  # canto superior esquerdo da chama
FLAME_CENTER = (15.5, 8.0)
SPARKS = [(10, 5, "Y"), (22, 6, "O"), (21, 1, "Y"), (8, 9, "O")]
STARS = [(3, 3, STAR), (27, 4, STAR), (6, 11, STAR_DIM), (29, 13, STAR_DIM), (23, 2, STAR_DIM)]

FONT = {  # 5x7
    "C": [".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."],
    "A": [".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"],
    "I": ["#####", "..#..", "..#..", "..#..", "..#..", "..#..", "#####"],
    "R": ["####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"],
    "N": ["#...#", "##..#", "##..#", "#.#.#", "#..##", "#..##", "#...#"],
    "F": ["#####", "#....", "#....", "####.", "#....", "#....", "#...."],
    "E": ["#####", "#....", "#....", "####.", "#....", "#....", "#####"],
}
FIRE_ROWS = [rgb(c) for c in ("#fff3b0", "#ffc933", "#ffc933", "#ff8a1f", "#ff8a1f", "#d0391f", "#d0391f")]

BAYER = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]


def bayer(x, y):
    return (BAYER[y % 4][x % 4] + 0.5) / 16 - 0.5  # -0.5 .. 0.5


class Canvas:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.px = [[None] * w for _ in range(h)]

    def put(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = c

    def image(self, scale=1):
        img = Image.new("RGB", (self.w, self.h))
        img.putdata([c for row in self.px for c in row])
        return img if scale == 1 else img.resize((self.w * scale, self.h * scale), Image.NEAREST)

    def svg(self):
        rects = []
        for y, row in enumerate(self.px):
            x = 0
            while x < self.w:
                x2 = x
                while x2 + 1 < self.w and row[x2 + 1] == row[x]:
                    x2 += 1
                rects.append(f'<rect x="{x}" y="{y}" width="{x2 - x + 1}" height="1" fill="#{row[x][0]:02x}{row[x][1]:02x}{row[x][2]:02x}"/>')
                x = x2 + 1
        return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {self.w} {self.h}" '
                f'shape-rendering="crispEdges">\n' + "\n".join(rects) + "\n</svg>\n")


def light(x, y, cx, cy, rx, ry=None):
    """Intensidade da luz da chama (0..1) no pixel (x, y); rx/ry são os raios do brilho."""
    return max(0.0, 1 - math.hypot((x - cx) / rx, (y - cy) / (ry or rx)))


def noise(x, y):
    return (x * 7919 + y * 104729 + x * y * 31) % 100


def draw_cairn(c, ox, oy, glow_rx, glow_ry):
    fx, fy = FLAME_CENTER[0] + ox, FLAME_CENTER[1] + oy

    # fundo: noite com o brilho da chama em degraus (dithering)
    for y in range(c.h):
        for x in range(c.w):
            t = light(x, y, fx, fy, glow_rx, glow_ry)
            level = sum(t + bayer(x, y) * 0.14 > th for th in (0.18, 0.40, 0.68))
            c.put(x, y, BG[level])
    for sx, sy, col in STARS:
        if light(sx + ox, sy + oy, fx, fy, glow_rx, glow_ry) < 0.1:
            c.put(sx + ox, sy + oy, col)

    # pedras
    for x0, x1, y0, y1, ch in STONES:
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                dx, dy = min(x - x0, x1 - x), min(y - y0, y1 - y)
                if dx + dy < ch:
                    continue  # canto arredondado
                lv = 1
                if y == y0 or x == x0:
                    lv = 2
                if y == y1 or x == x1:
                    lv = 0
                if y == y0 and x == x0 + ch:
                    lv = 3
                elif 0 < dx and 0 < dy:
                    n = noise(x, y)
                    lv = 0 if n < 5 else 2 if n < 12 else lv
                # iluminação: perto da chama a pedra fica quente
                t = light(x, y, FLAME_CENTER[0], FLAME_CENTER[1] + 1, 15)
                warm = t + bayer(x, y) * 0.35 > 0.3
                c.put(x + ox, y + oy, (WARM if warm else COOL)[lv])

    # chama e faíscas
    for j, line in enumerate(FLAME):
        for i, ch in enumerate(line):
            if ch != ".":
                c.put(FLAME_POS[0] + i + ox, FLAME_POS[1] + j + oy, FIRE[ch])
    for sx, sy, ch in SPARKS:
        c.put(sx + ox, sy + oy, FIRE[ch])

    # chão
    for x in range(c.w):
        c.put(x, 31 + oy, GROUND)


def stone_letter_color(gx, gy, r, fx, fy):
    """Cor de um pixel de letra de pedra: mais quente e clara quanto mais perto da chama."""
    lv = 3 if r == 0 else 2 if r < 5 else 1
    t = light(gx, gy, fx, fy, 95)
    step = min(3, int(t * 4.5))  # 0..3 passos de aquecimento
    cool, warm = COOL[lv], WARM[lv]
    k = step / 3
    return tuple(round(cool[i] + (warm[i] - cool[i]) * k) for i in range(3))


def draw_title(c, x0, y0, fx, fy, s=3):
    """Escreve CAIRNFIRE (fonte 5x7 ampliada s vezes). CAIRN é pedra iluminada pelo fogo; FIRE arde."""
    step = 6 * s
    for idx, letter in enumerate("CAIRNFIRE"):
        x = x0 + idx * step
        is_fire = idx >= 5
        cells = [(i, r) for r, line in enumerate(FONT[letter]) for i, b in enumerate(line) if b == "#"]
        shadow = rgb("#4a1414") if is_fire else GROUND
        for i, r in cells:  # sombra por baixo e à direita
            for dy in range(s):
                for dx in range(s):
                    c.put(x + i * s + dx + 1, y0 + r * s + dy + 1, shadow)
        for i, r in cells:
            for dy in range(s):
                for dx in range(s):
                    gx, gy = x + i * s + dx, y0 + r * s + dy
                    c.put(gx, gy, FIRE_ROWS[r] if is_fire else stone_letter_color(gx, gy, r, fx, fy))


def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.width - 1, img.height - 1), radius, fill=255)
    out = img.convert("RGBA")
    out.putalpha(mask)
    return out


def write(path, data, mode="w"):
    with open(os.path.join(OUT, path), mode) as f:
        f.write(data)
    print("escrito", path)


def main():
    # ---- símbolo (32x32) ----
    sym = Canvas(32, 32)
    draw_cairn(sym, 0, 0, 15, 15)
    sym.image(16).save(os.path.join(OUT, "logo.png"))
    write("logo.svg", sym.svg())

    # ---- banner: símbolo + título ----
    ban = Canvas(216, 40)
    draw_cairn(ban, 4, 4, 60, 30)
    for x in range(ban.w):  # o chão passa por baixo do título
        ban.put(x, 35, GROUND)
    draw_title(ban, 46, 14, FLAME_CENTER[0] + 4, FLAME_CENTER[1] + 4)
    ban.image(8).save(os.path.join(OUT, "banner.png"))
    write("banner.svg", ban.svg())

    # ---- banner para a janela da app: com margem, para o brilho se desvanecer antes das bordas ----
    ui = Canvas(232, 44)
    draw_cairn(ui, 20, 10, 32, 16)
    draw_title(ui, 62, 20, FLAME_CENTER[0] + 20, FLAME_CENTER[1] + 10)
    ui.image(4).save(os.path.join(OUT, "banner-ui.png"))
    print("escrito banner-ui.png")

    # ---- ícones ----
    base = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
    art = rounded(sym.image(25), 170)  # 800x800, cantos arredondados, com margem como nos ícones do macOS
    base.paste(art, (112, 112), art)
    base.save(os.path.join(OUT, "icon-1024.png"))

    ico_src = rounded(sym.image(8), 40)  # 256x256
    ico_src.save(os.path.join(OUT, "icon.ico"), sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    print("escrito icon.ico")

    if shutil.which("iconutil"):
        with tempfile.TemporaryDirectory() as tmp:
            iconset = os.path.join(tmp, "icon.iconset")
            os.mkdir(iconset)
            for size in (16, 32, 128, 256, 512):
                for factor, suffix in ((1, ""), (2, "@2x")):
                    px = size * factor
                    base.resize((px, px), Image.NEAREST if px >= 64 else Image.LANCZOS).save(
                        os.path.join(iconset, f"icon_{size}x{size}{suffix}.png"))
            subprocess.run(["iconutil", "-c", "icns", iconset, "-o", os.path.join(OUT, "icon.icns")], check=True)
        print("escrito icon.icns")
    else:
        print("aviso: sem iconutil (não é macOS); icon.icns não foi gerado", file=sys.stderr)


if __name__ == "__main__":
    main()
