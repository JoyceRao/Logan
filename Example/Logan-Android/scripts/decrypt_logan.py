#!/usr/bin/env python3
"""Decrypt a Logan Android log file.

The CLogan writer stores each flush unit as:
    0x01 | 4-byte big-endian encrypted length | AES-CBC bytes | 0x00

Each encrypted payload is gzip data padded to an AES block boundary.
"""

from __future__ import annotations

import argparse
import gzip
import shutil
import subprocess
import sys
from pathlib import Path


HEADER = 0x01
TAIL = 0x00
BLOCK_SIZE = 16
ENCRYPT_KEY_16 = b"chd202012loganke"
ENCRYPT_IV_16 = b"chd202012loganiv"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Decrypt a Logan log file."
    )
    parser.add_argument("logPath", help="Path to the encrypted Logan log file")
    return parser.parse_args()


def aes_cbc_decrypt(ciphertext: bytes, key: bytes, iv: bytes) -> bytes:
    try:
        from Crypto.Cipher import AES  # type: ignore

        return AES.new(key, AES.MODE_CBC, iv).decrypt(ciphertext)
    except ImportError:
        return aes_cbc_decrypt_with_openssl(ciphertext, key, iv)


def aes_cbc_decrypt_with_openssl(ciphertext: bytes, key: bytes, iv: bytes) -> bytes:
    openssl = shutil.which("openssl")
    if openssl is None:
        raise RuntimeError(
            "AES backend not found. Install pycryptodome (`python3 -m pip install "
            "pycryptodome`) or make sure `openssl` is available on PATH."
        )

    result = subprocess.run(
        [
            openssl,
            "enc",
            "-aes-128-cbc",
            "-d",
            "-nopad",
            "-K",
            key.hex(),
            "-iv",
            iv.hex(),
        ],
        input=ciphertext,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.decode("utf-8", errors="replace").strip())
    return result.stdout


def remove_padding(data: bytes) -> bytes:
    if not data:
        raise ValueError("empty decrypted payload")

    padding = data[-1]
    if padding < 1 or padding > BLOCK_SIZE:
        raise ValueError(f"invalid padding length: {padding}")
    if data[-padding:] != bytes([padding]) * padding:
        raise ValueError("invalid padding bytes")
    return data[:-padding]


def decrypt_units(encrypted_file: bytes, key: bytes, iv: bytes) -> tuple[bytes, list[str]]:
    offset = 0
    unit_index = 0
    output = bytearray()
    warnings: list[str] = []

    while offset < len(encrypted_file):
        if encrypted_file[offset] != HEADER:
            if encrypted_file[offset:].strip(b"\x00") == b"":
                break
            next_header = encrypted_file.find(bytes([HEADER]), offset + 1)
            if next_header < 0:
                warnings.append(f"stopped at offset {offset}: missing unit header")
                break
            warnings.append(
                f"skipped {next_header - offset} bytes before next header at offset {next_header}"
            )
            offset = next_header
            continue

        if offset + 5 > len(encrypted_file):
            warnings.append(f"stopped at offset {offset}: truncated unit header")
            break

        content_len = int.from_bytes(encrypted_file[offset + 1 : offset + 5], "big")
        payload_start = offset + 5
        payload_end = payload_start + content_len
        tail_offset = payload_end

        if content_len <= 0 or content_len % BLOCK_SIZE != 0:
            warnings.append(
                f"stopped at unit {unit_index}: invalid encrypted length {content_len}"
            )
            break
        if tail_offset >= len(encrypted_file):
            warnings.append(f"stopped at unit {unit_index}: truncated encrypted payload")
            break
        if encrypted_file[tail_offset] != TAIL:
            warnings.append(
                f"unit {unit_index}: expected tail 0x00 at offset {tail_offset}, "
                f"got 0x{encrypted_file[tail_offset]:02x}"
            )

        ciphertext = encrypted_file[payload_start:payload_end]
        try:
            padded_gzip = aes_cbc_decrypt(ciphertext, key, iv)
            gzip_payload = remove_padding(padded_gzip)
            output.extend(gzip.decompress(gzip_payload))
        except Exception as exc:
            warnings.append(f"unit {unit_index}: decrypt/decompress failed: {exc}")

        offset = tail_offset + 1
        unit_index += 1

    return bytes(output), warnings


def main() -> int:
    args = parse_args()

    try:
        log_path = Path(args.logPath)
        decrypted, warnings = decrypt_units(
            log_path.read_bytes(), ENCRYPT_KEY_16, ENCRYPT_IV_16
        )
    except Exception as exc:
        print(f"decrypt_logan.py: {exc}", file=sys.stderr)
        return 1

    output_path = log_path.with_suffix(log_path.suffix + ".decrypted.log")
    output_path.write_bytes(decrypted)
    print(f"wrote {len(decrypted)} bytes to {output_path}")

    for warning in warnings:
        print(f"warning: {warning}", file=sys.stderr)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
