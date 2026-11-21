#!/usr/bin/env python3
"""
Receptor de prueba para la salida de rostro de MoonyFace (puerto 49983).

Uso (en la PC, misma red Wi-Fi que el teléfono):
    python ifm_receiver_test.py 192.168.1.50        # IP del teléfono que muestra la app

Envía el saludo de conexión al teléfono y muestra en vivo algunos valores.
"""
import socket
import sys
import time

PORT = 49983
HANDSHAKE = b"iFacialMocap_sahuasouryya9218sauhuiayeta91555dy3719"
WATCH = ["jawOpen", "eyeBlink_L", "eyeBlink_R", "mouthSmile_L", "browInnerUp", "cheekPuff", "tongueOut"]


def parse(packet: str):
    shapes, transforms = {}, {}
    body, _, rest = packet.partition("=")
    for item in body.split("|"):
        if not item:
            continue
        sep = "&" if "&" in item else "-"
        name, _, value = item.rpartition(sep)
        try:
            shapes[name] = int(value)
        except ValueError:
            pass
    for item in rest.split("|"):
        if "#" in item:
            name, _, values = item.partition("#")
            transforms[name] = [float(v) for v in values.split(",") if v]
    return shapes, transforms


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    phone = sys.argv[1]
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("", PORT))
    sock.settimeout(1.0)
    last_hs = 0.0
    count, t0 = 0, time.time()
    while True:
        if time.time() - last_hs > 2:
            sock.sendto(HANDSHAKE, (phone, PORT))
            last_hs = time.time()
        try:
            data, _ = sock.recvfrom(8192)
        except socket.timeout:
            print("esperando datos del teléfono...")
            continue
        count += 1
        shapes, tr = parse(data.decode("utf-8", "replace"))
        if count % 6 == 0:
            fps = count / max(time.time() - t0, 1e-3)
            vals = "  ".join(f"{k}={shapes.get(k, '?'):>3}" for k in WATCH)
            head = tr.get("head", [0] * 6)
            print(f"{fps:5.1f}fps  {vals}  head p{head[0]:6.1f} y{head[1]:6.1f} r{head[2]:6.1f}  ({len(shapes)} shapes)")


if __name__ == "__main__":
    main()
