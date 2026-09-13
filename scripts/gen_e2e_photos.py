#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
gen_e2e_photos.py — 端到端验证用测试图片生成（photo-semantic-search 5.3/6.4）
------------------------------------------------------------
【用途】生成两类合成照片，供真实模型栈的端到端验证：
  1. 主图 1 张：红花.jpg —— 供 caption="红色的小花" 的完整链路验证
     （mock 标注 caption 优先 → 描述向量路命中 → 重排分）
  2. 缩样 N 张（默认 100）：主题多样的几何构图 + 中文描述性文件名
     —— 供批量导入缩样实测（进度可见/期间检索可用/规模推算）。
     描述性文件名是关键：mock 标注器按文件名派生标注，
     文件名即"这张图是什么"，导入后描述路检索按名字可命中。

【为什么几何图就够了】5.3 验证的是管线行为（进度/可用性/双路命中）
而非 CLIP 像素精度——描述路命中由「查询文本 ≈ 标注描述」的 bge
相似度决定，与像素内容无关；像素路是否命中不在验收项里。

用法：python gen_e2e_photos.py <输出目录> [缩样张数，默认100]
"""
import os
import random
import sys

from PIL import Image, ImageDraw

# 主题池：颜色（花瓣/主体）+ 文件名模板——名字描述内容，mock 标注按此派生
THEMES = [
    ("红", "红花", (220, 60, 60)),
    ("黄", "黄花", (235, 200, 60)),
    ("白", "白花", (240, 240, 240)),
    ("蓝", "蓝色的湖面", (70, 130, 200)),
    ("绿", "绿色的山", (80, 160, 90)),
    ("橙", "夕阳", (240, 150, 70)),
    ("灰", "石板路", (150, 150, 150)),
    ("紫", "薰衣草", (160, 100, 200)),
]


def draw_flower(draw, w, h, petal_color):
    """一朵简笔花：花瓣环 + 花心 + 茎 + 两片叶"""
    cx, cy = w // 2, int(h * 0.42)
    petal_r = int(min(w, h) * 0.14)
    for angle_deg in range(0, 360, 45):
        rad = angle_deg * 3.14159 / 180
        px = cx + int(petal_r * 1.3 * __import__("math").cos(rad))
        py = cy + int(petal_r * 1.3 * __import__("math").sin(rad))
        draw.ellipse([px - petal_r, py - petal_r, px + petal_r, py + petal_r], fill=petal_color)
    draw.ellipse([cx - petal_r, cy - petal_r, cx + petal_r, cy + petal_r],
                 fill=(250, 220, 90))
    draw.line([cx, cy, cx, h - 10], fill=(60, 130, 60), width=6)
    draw.ellipse([cx - 40, int(h * 0.7), cx - 4, int(h * 0.7) + 24], fill=(60, 150, 60))
    draw.ellipse([cx + 4, int(h * 0.78), cx + 40, int(h * 0.78) + 24], fill=(60, 150, 60))


def draw_scene(draw, w, h, color):
    """简笔风景：天空渐变带 + 地平线 + 主体色块（山/湖/夕阳任选构图）"""
    draw.rectangle([0, 0, w, int(h * 0.55)], fill=(200, 220, 240))   # 天空
    draw.rectangle([0, int(h * 0.55), w, h], fill=color)             # 地面/水面
    draw.ellipse([int(w * 0.6), int(h * 0.12), int(w * 0.78), int(h * 0.30)],
                 fill=(250, 230, 120))                               # 太阳
    draw.polygon([(0, int(h * 0.55)), (int(w * 0.3), int(h * 0.30)),
                  (int(w * 0.55), int(h * 0.55))], fill=(90, 110, 130))  # 远山


def make_photo(path, theme, variant, flower):
    w, h = 480, 360
    img = Image.new("RGB", (w, h))
    draw = ImageDraw.Draw(img)
    color = theme[2]
    if flower:
        draw.rectangle([0, 0, w, h], fill=(230, 245, 230))
        draw_flower(draw, w, h, color)
    else:
        draw_scene(draw, w, h, color)
    # 每张轻微差异（大小/位置扰动靠随机种子），避免"完全相同的字节流"
    draw.rectangle([0, 0, w, 4], fill=(variant % 255, 0, 0))
    img.save(path, "JPEG", quality=85)


def main():
    if len(sys.argv) < 2:
        print("用法: python gen_e2e_photos.py <输出目录> [缩样张数]")
        return 1
    out_dir = sys.argv[1]
    count = int(sys.argv[2]) if len(sys.argv) > 2 else 100
    os.makedirs(out_dir, exist_ok=True)
    random.seed(20260913)

    # 主图：红花（6.4 端到端主角，caption="红色的小花"）
    make_photo(os.path.join(out_dir, "红花.jpg"), THEMES[0], 0, flower=True)

    # 缩样：主题轮换 + 序号文件名（描述性名字 + 序号保证唯一）
    for i in range(count):
        theme = THEMES[i % len(THEMES)]
        flower = i % 2 == 0
        name = "{}{:03d}.jpg".format(theme[1], i + 1)
        make_photo(os.path.join(out_dir, name), theme, i, flower)

    print("[gen_e2e_photos] 生成完成: {}（主图 1 张 + 缩样 {} 张）".format(out_dir, count))
    return 0


if __name__ == "__main__":
    sys.exit(main())
