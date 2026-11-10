"""Generate synthetic D2 GEO wire pages and pinned RTKLIB C parameter results.

Requires rcvraw.c, rtkcmn.c and rtklib.h from RTKLIB commit
71db0ffa0d9735697c6adfd06fdf766d0e5ce807. Not captured navigation data.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import subprocess
import tempfile

from generate_beidou_d1_reference import bch


def build(values):
    pages = [[0] * 300 for _ in range(10)]

    def field(ranges, value):
        count = sum(length for _, _, length in ranges)
        assert -(1 << (count - 1)) <= value < 1 << count
        value &= (1 << count) - 1
        for page, start, length in ranges:
            count -= length
            for offset in range(length):
                pages[page - 1][start + offset] = (value >> (count + length - 1 - offset)) & 1

    for page in range(1, 11):
        field([(page, 0, 11)], 0x712)
        field([(page, 15, 3)], 1)
        field([(page, 18, 8), (page, 30, 12)], 432000 + (page - 1) * 3)
        field([(page, 42, 4)], page)
    for name, ranges in [
        ("health", [(1, 46, 1)]), ("aodc", [(1, 47, 5)]), ("ura", [(1, 60, 4)]),
        ("week", [(1, 64, 13)]), ("tocUnits", [(1, 77, 5), (1, 90, 12)]),
        ("tgd1", [(1, 102, 10)]), ("tgd2", [(1, 120, 10)]),
        ("af0", [(3, 100, 12), (3, 120, 12)]), ("af1", [(3, 132, 4), (4, 46, 6), (4, 60, 12)]),
        ("af2", [(4, 72, 10), (4, 90, 1)]), ("aode", [(4, 91, 5)]), ("deltaN", [(4, 96, 16)]),
        ("cuc", [(4, 120, 14), (5, 46, 4)]), ("m0", [(5, 50, 2), (5, 60, 22), (5, 90, 8)]),
        ("cus", [(5, 98, 14), (5, 120, 4)]), ("e", [(5, 124, 10), (6, 46, 6), (6, 60, 16)]),
        ("sqrtA", [(6, 76, 6), (6, 90, 22), (6, 120, 4)]),
        ("cic", [(6, 124, 10), (7, 46, 6), (7, 60, 2)]), ("cis", [(7, 62, 18)]),
        ("tocUnits", [(7, 80, 2), (7, 90, 15)]),
        ("i0", [(7, 105, 7), (7, 120, 14), (8, 46, 6), (8, 60, 5)]),
        ("crc", [(8, 65, 17), (8, 90, 1)]), ("crs", [(8, 91, 18)]),
        ("omegaDot", [(8, 109, 3), (8, 120, 16), (9, 46, 5)]),
        ("omega0", [(9, 51, 1), (9, 60, 22), (9, 90, 9)]),
        ("omega", [(9, 99, 13), (9, 120, 14), (10, 46, 5)]),
        ("iDot", [(10, 51, 1), (10, 60, 13)]),
    ]:
        field(ranges, values[name])

    def integer(bits):
        return int("".join(map(str, bits)), 2)

    logical = b"".join(integer(page + [0] * 4).to_bytes(38, "big") for page in pages)
    wire = []
    for page in pages:
        words = [(integer(page[:26]) << 4) | bch(integer(page[15:26]))]
        for start in range(30, 300, 30):
            a, b = integer(page[start:start + 11]), integer(page[start + 11:start + 22])
            a, b = a << 4 | bch(a), b << 4 | bch(b)
            words.append(sum(((a >> bit & 1) << (2 * bit + 1)) | ((b >> bit & 1) << (2 * bit))
                             for bit in range(15)))
        wire.append(base64.b64encode(b"".join(word.to_bytes(4, "big") for word in words)).decode())
    return logical, wire


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rtklib-dir", type=Path, required=True)
    parser.add_argument("--cc", default="gcc")
    args = parser.parse_args()
    fields = ["sqrtA", "e", "i0", "omega0", "omega", "m0", "deltaN", "omegaDot", "iDot",
              "crc", "crs", "cuc", "cus", "cic", "cis", "af0", "af1", "af2", "tgd1", "tgd2"]
    members = ["sqrt(e.A)", "e.e", "e.i0", "e.OMG0", "e.omg", "e.M0", "e.deln", "e.OMGd", "e.idot",
               "e.crc", "e.crs", "e.cuc", "e.cus", "e.cic", "e.cis", "e.f0", "e.f1", "e.f2",
               "e.tgd[0]", "e.tgd[1]"]
    records = []
    with tempfile.TemporaryDirectory(prefix="beidou-d2-") as temp:
        temp = Path(temp)
        source = (args.rtklib_dir / "rcvraw.c").read_text()
        marker = "/* test hamming code of glonass ephemeris string"
        if source.count(marker) != 1:
            raise ValueError("unexpected rcvraw.c layout; use pinned commit")
        (temp / "prefix.c").write_text(source.split(marker)[0])
        driver = '#include "rtklib.h"\nint main(void){unsigned char b[380]; unsigned v; eph_t e={0};'
        driver += 'for(int i=0;i<380;i++){if(scanf("%2x",&v)!=1)return 2;b[i]=v;}'
        driver += 'if(!decode_bds_d2(b,&e))return 3;'
        driver += 'printf("%d %d %d %d %d %.0f",e.week,e.svh,e.sva,e.iodc,e.iode,e.toes);'
        driver += ''.join(f'printf(" %.17g",{member});' for member in members) + 'return 0;}'
        (temp / "driver.c").write_text(driver)
        executable = temp / "reference.exe"
        command = [args.cc, "-std=c99", "-O2", "-DENACMP", "-I", str(args.rtklib_dir),
                   str(temp / "driver.c"), str(temp / "prefix.c"), str(args.rtklib_dir / "rtkcmn.c")]
        command += ["-DWIN32", "-lwinmm", "-lws2_32"] if os.name == "nt" else ["-lm", "-lpthread"]
        subprocess.run(command + ["-o", str(executable)], check=True)
        for sign in (-1, 1):
            values = dict(week=700, health=0, ura=5, aodc=3, aode=4, tocUnits=54000,
                          sqrtA=3405457299, e=85899345, i0=sign * 47000003,
                          omega0=sign * 500000007, omega=-sign * 400000009, m0=sign * 700000011,
                          deltaN=sign * 12003, omegaDot=-sign * 234567, iDot=sign * 5123,
                          cuc=-sign * 10003, cus=sign * 20005, crc=-sign * 12345, crs=sign * 54321,
                          cic=sign * 7777, cis=-sign * 9999, af0=-sign * 345678,
                          af1=sign * 456789, af2=-sign * 523, tgd1=sign * 345, tgd2=-sign * 234)
            logical, wire = build(values)
            result = subprocess.run([str(executable)], input=logical.hex(), text=True, capture_output=True, check=True)
            expected = dict(zip(["week", "health", "ura", "aodc", "aode", "toe"] + fields,
                                map(float, result.stdout.split())))
            records.append(dict(rawUnits=values, payloads=wire, expected=expected))
    output = dict(reference="https://github.com/tomojitakasu/RTKLIB/blob/"
                  "71db0ffa0d9735697c6adfd06fdf766d0e5ce807/src/rcvraw.c",
                  method="Synthetic ICD D2 GEO logical fields encoded to BCH/interleaved Android words; "
                  "independent RTKLIB C decode_bds_d2. Not captured receiver navigation data.", records=records)
    target = Path(__file__).resolve().parents[1] / "core/src/test/resources/gnss/beidou-d2-reference.json"
    target.write_text(json.dumps(output, indent=2) + "\n")
    print("Generated two independent C D2 GEO field vectors with synthetic wire pages")


if __name__ == "__main__":
    main()
