# /// script
# requires-python = ">=3.11"
# dependencies = ["qrcode[pil]>=7.4"]
# ///
"""
Generate a SeekerZero setup QR code for sideload/device testing.

Output matches the format consumed by QrParser.kt:
  seekerzero://config?payload=<base64url(no-padding) of JSON>

JSON schema (v=1):
  {v, a0_host, port, mobile_api_base, client_id, display_name}

Usage:
  uv run tools/generate_qr.py
  uv run tools/generate_qr.py --a0-host a0prod.your-tailnet.ts.net --port 50080
  uv run tools/generate_qr.py --raw-payload   # emit just the base64 (no URI wrapper)
"""

from __future__ import annotations

import argparse
import base64
import json
import sys
from pathlib import Path

import qrcode


DEFAULTS = {
    "a0_host": "a0prod.your-tailnet.ts.net",
    "port": 50080,
    "mobile_api_base": "/mobile",
    "client_id": "seekerzero-james-seeker",
    "display_name": "James's Seeker",
}


def build_payload(
    a0_host: str,
    port: int,
    mobile_api_base: str,
    client_id: str,
    display_name: str,
) -> dict:
    return {
        "v": 1,
        "a0_host": a0_host,
        "port": port,
        "mobile_api_base": mobile_api_base,
        "client_id": client_id,
        "display_name": display_name,
    }


def encode(payload: dict) -> str:
    raw = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def main() -> int:
    ap = argparse.ArgumentParser(description="Generate SeekerZero setup QR.")
    ap.add_argument("--a0-host", default=DEFAULTS["a0_host"])
    ap.add_argument("--port", type=int, default=DEFAULTS["port"])
    ap.add_argument("--mobile-api-base", default=DEFAULTS["mobile_api_base"])
    ap.add_argument("--client-id", default=DEFAULTS["client_id"])
    ap.add_argument("--display-name", default=DEFAULTS["display_name"])
    ap.add_argument(
        "--output",
        "-o",
        default=str(Path(__file__).resolve().parent.parent / "seeker-config-qr.png"),
        help="PNG output path (default: repo-root/seeker-config-qr.png)",
    )
    ap.add_argument(
        "--raw-payload",
        action="store_true",
        help="Encode only the base64 payload in the QR (no seekerzero:// URI wrapper).",
    )
    ap.add_argument("--box-size", type=int, default=10)
    ap.add_argument("--border", type=int, default=4)
    args = ap.parse_args()

    if not 1 <= args.port <= 65535:
        ap.error(f"--port must be in 1..65535 (got {args.port})")

    payload = build_payload(
        args.a0_host, args.port, args.mobile_api_base, args.client_id, args.display_name
    )
    b64 = encode(payload)
    qr_content = b64 if args.raw_payload else f"seekerzero://config?payload={b64}"

    qr = qrcode.QRCode(
        version=None,
        error_correction=qrcode.constants.ERROR_CORRECT_M,
        box_size=args.box_size,
        border=args.border,
    )
    qr.add_data(qr_content)
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white")

    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)

    print(f"payload JSON : {json.dumps(payload, separators=(',', ':'))}")
    print(f"base64url    : {b64}")
    print(f"QR content   : {qr_content}")
    print(f"wrote        : {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
