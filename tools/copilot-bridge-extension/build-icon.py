#!/usr/bin/env python3
# Renders a 128x128 PNG icon for the ai-bench Copilot Bridge VSCode
# extension. The design: two rounded "node" pillars on a deep teal
# field, connected by a white arch with a single bright spark at its
# apex — symbolizing the bridge that relays Copilot signals from one
# side (the IDE) to the other (the bench harness).
#
# Re-run this script whenever the design needs to change. The output
# overwrites tools/copilot-bridge-extension/icon.png and is checked
# into the repo so contributors don't need PIL installed.

from PIL import Image, ImageDraw
import os, sys

SIZE = 128
BG_COLOR     = (14, 79, 92, 255)     # deep teal — distinctive vs typical extension blues
PILLAR_COLOR = (240, 245, 248, 255)  # near-white
ARCH_COLOR   = (240, 245, 248, 255)
SPARK_COLOR  = (255, 196, 41, 255)   # warm gold — the "signal" dot
ACCENT_COLOR = (61, 196, 213, 255)   # secondary cyan accent

def rounded_rect(draw, box, radius, fill):
    x0, y0, x1, y1 = box
    draw.rounded_rectangle((x0, y0, x1, y1), radius=radius, fill=fill)

def main():
    img = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Backdrop: rounded square. ~14% radius reads as "soft tile" without
    # turning into a circle.
    rounded_rect(draw, (0, 0, SIZE, SIZE), radius=18, fill=BG_COLOR)

    # Two pillars — left and right. Tall narrow rectangles with rounded
    # tops, capped by circular nodes.
    pillar_w   = 14
    pillar_top = 50
    pillar_bot = 104
    left_x  = 26
    right_x = SIZE - 26 - pillar_w

    rounded_rect(draw, (left_x,  pillar_top, left_x  + pillar_w, pillar_bot),
                 radius=4, fill=PILLAR_COLOR)
    rounded_rect(draw, (right_x, pillar_top, right_x + pillar_w, pillar_bot),
                 radius=4, fill=PILLAR_COLOR)

    # Node caps on top of each pillar.
    node_r = 11
    left_cx  = left_x  + pillar_w // 2
    right_cx = right_x + pillar_w // 2
    draw.ellipse((left_cx  - node_r, pillar_top - node_r,
                  left_cx  + node_r, pillar_top + node_r), fill=ACCENT_COLOR)
    draw.ellipse((right_cx - node_r, pillar_top - node_r,
                  right_cx + node_r, pillar_top + node_r), fill=ACCENT_COLOR)

    # White rim on the nodes for crispness.
    rim = 3
    draw.ellipse((left_cx  - node_r, pillar_top - node_r,
                  left_cx  + node_r, pillar_top + node_r),
                 outline=PILLAR_COLOR, width=rim)
    draw.ellipse((right_cx - node_r, pillar_top - node_r,
                  right_cx + node_r, pillar_top + node_r),
                 outline=PILLAR_COLOR, width=rim)

    # Bridge arch from left node to right node. Drawn as a thick chord
    # of an ellipse so the curve has a satisfying weight on both small
    # and large rendering sizes.
    arch_top = 16
    arch_bot = pillar_top + 6
    draw.arc((left_cx - 6, arch_top, right_cx + 6, arch_bot * 2 - arch_top),
             start=180, end=360, fill=ARCH_COLOR, width=6)

    # Spark at the apex — the "signal" flowing across the bridge.
    spark_cx = SIZE // 2
    spark_cy = arch_top + 4
    draw.ellipse((spark_cx - 7, spark_cy - 7, spark_cx + 7, spark_cy + 7),
                 fill=SPARK_COLOR)
    # Inner highlight so it reads as a glowing dot at small sizes.
    draw.ellipse((spark_cx - 3, spark_cy - 3, spark_cx + 3, spark_cy + 3),
                 fill=(255, 240, 200, 255))

    # Floor strip with two short ticks beneath each pillar, hinting
    # "devtool" without being literal.
    floor_y = pillar_bot + 2
    draw.line((left_cx - 12, floor_y, left_cx + 12, floor_y),
              fill=PILLAR_COLOR, width=3)
    draw.line((right_cx - 12, floor_y, right_cx + 12, floor_y),
              fill=PILLAR_COLOR, width=3)

    out = os.path.join(os.path.dirname(__file__), 'icon.png')
    img.save(out, 'PNG', optimize=True)
    print(f'wrote {out} ({os.path.getsize(out)} bytes, {SIZE}x{SIZE})')

if __name__ == '__main__':
    sys.exit(main() or 0)
