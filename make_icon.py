#!/usr/bin/env python3
"""把任意图片裁成方形并生成 App 图标（res/mipmap/ic_launcher.png）。

用法:  python3 make_icon.py <输入图片> [尺寸，默认192]
"""
import sys
from PIL import Image

SRC = sys.argv[1] if len(sys.argv) > 1 else None
SIZE = int(sys.argv[2]) if len(sys.argv) > 2 else 192
OUT = "res/mipmap/ic_launcher.png"

if not SRC:
    sys.exit("用法: python3 make_icon.py <图片> [尺寸]")

im = Image.open(SRC).convert("RGBA")
w, h = im.size
side = min(w, h)
box = ((w - side) // 2, (h - side) // 2, (w + side) // 2, (h + side) // 2)
icon = im.crop(box).resize((SIZE, SIZE), Image.LANCZOS)
icon.save(OUT)
print(f"源图 {w}x{h} → 居中裁 {side}x{side} → {OUT} ({SIZE}x{SIZE})")
