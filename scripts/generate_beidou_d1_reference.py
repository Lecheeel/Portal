"""Generate synthetic D1 wire messages and independent pinned RTKLIB field results.

Requires rcvraw.c, rtkcmn.c and rtklib.h from RTKLIB commit
71db0ffa0d9735697c6adfd06fdf766d0e5ce807. Not real receiver evidence.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import subprocess
import tempfile


def bch(data):
    # Polynomial long division, g(x) = x^4 + x + 1.
    dividend = data << 4
    for power in range(14, 3, -1):
        if dividend & (1 << power):
            dividend ^= 0x13 << (power - 4)
    return dividend


def build(values):
    frames = [[0] * 300 for _ in range(3)]

    def field(frame, ranges, value):
        count = sum(length for _, length in ranges)
        assert -(1 << (count - 1)) <= value < 1 << count
        value &= (1 << count) - 1
        for start, length in ranges:
            count -= length
            for offset in range(length):
                frame[start + offset] = (value >> (count + length - 1 - offset)) & 1

    for i, frame in enumerate(frames):
        field(frame, [(0, 11)], 0x712)
        field(frame, [(15, 3)], i + 1)
        field(frame, [(18, 8), (30, 12)], 432000 + i * 6)
    for frame, ranges, name in [
        (0, [(42, 1)], "health"), (0, [(43, 5)], "aodc"), (0, [(48, 4)], "ura"),
        (0, [(60, 13)], "week"), (0, [(73, 9), (90, 8)], "tocUnits"),
        (0, [(98, 10)], "tgd1"), (0, [(108, 4), (120, 6)], "tgd2"),
        (0, [(214, 11)], "af2"), (0, [(225, 7), (240, 17)], "af0"),
        (0, [(257, 5), (270, 17)], "af1"), (0, [(287, 5)], "aode"),
        (1, [(42, 10), (60, 6)], "deltaN"), (1, [(66, 16), (90, 2)], "cuc"),
        (1, [(92, 20), (120, 12)], "m0"), (1, [(132, 10), (150, 22)], "e"),
        (1, [(180, 18)], "cus"), (1, [(198, 4), (210, 14)], "crc"),
        (1, [(224, 8), (240, 10)], "crs"), (1, [(250, 12), (270, 20)], "sqrtA"),
        (2, [(65, 17), (90, 15)], "i0"), (2, [(105, 7), (120, 11)], "cic"),
        (2, [(131, 11), (150, 13)], "omegaDot"), (2, [(163, 9), (180, 9)], "cis"),
        (2, [(189, 13), (210, 1)], "iDot"), (2, [(211, 21), (240, 11)], "omega0"),
        (2, [(251, 11), (270, 21)], "omega"),
    ]:
        field(frames[frame], ranges, values[name])
    field(frames[1], [(290, 2)], values["tocUnits"] >> 15)
    field(frames[2], [(42, 10), (60, 5)], values["tocUnits"] & 0x7fff)

    def integer(bits):
        return int("".join(map(str, bits)), 2)

    logical = b"".join(integer(frame + [0] * 4).to_bytes(38, "big") for frame in frames)
    wire = []
    for frame in frames:
        words = [(integer(frame[:26]) << 4) | bch(integer(frame[15:26]))]
        for start in range(30, 300, 30):
            a, b = integer(frame[start:start + 11]), integer(frame[start + 11:start + 22])
            a, b = a << 4 | bch(a), b << 4 | bch(b)
            transmitted = sum(((a >> bit & 1) << (2 * bit + 1)) |
                              ((b >> bit & 1) << (2 * bit)) for bit in range(15))
            words.append(transmitted)
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
    with tempfile.TemporaryDirectory(prefix="beidou-d1-") as temp:
        temp = Path(temp)
        source = (args.rtklib_dir / "rcvraw.c").read_text()
        marker = "/* decode BeiDou D2 ephemeris"
        if source.count(marker) != 1:
            raise ValueError("unexpected rcvraw.c layout; use pinned commit")
        (temp / "prefix.c").write_text(source.split(marker)[0])
        driver = '#include "rtklib.h"\nint main(void){unsigned char b[114]; unsigned v; eph_t e={0};'
        driver += 'for(int i=0;i<114;i++){if(scanf("%2x",&v)!=1)return 2;b[i]=v;}'
        driver += 'if(!decode_bds_d1(b,&e))return 3;'
        driver += 'printf("%d %d %d %d %d %.0f",e.week,e.svh,e.sva,e.iodc,e.iode,e.toes);'
        driver += ''.join(f'printf(" %.17g",{member});' for member in members) + 'return 0;}'
        (temp / "driver.c").write_text(driver)
        executable = temp / "reference.exe"
        command = [args.cc, "-std=c99", "-O2", "-DENACMP", "-I", str(args.rtklib_dir),
                   str(temp / "driver.c"), str(temp / "prefix.c"), str(args.rtklib_dir / "rtkcmn.c")]
        command += ["-DWIN32", "-lwinmm", "-lws2_32"] if os.name == "nt" else ["-lm", "-lpthread"]
        subprocess.run(command + ["-o", str(executable)], check=True)
        # Every split signed field has nonzero low bits; exercise both signs, no shared Kotlin code.
        for sign in (-1, 1):
            values = dict(week=700, health=0, ura=5, aodc=3, aode=4, tocUnits=54000,
                          sqrtA=2852126721, e=85899345, i0=650000003,
                          omega0=sign * 500000007, omega=-sign * 400000009, m0=sign * 700000011,
                          deltaN=sign * 12003, omegaDot=-sign * 234567, iDot=sign * 5123,
                          cuc=-sign * 10003, cus=sign * 20005, crc=-sign * 12345, crs=sign * 54321,
                          cic=sign * 7777, cis=-sign * 9999, af0=-sign * 345678,
                          af1=sign * 456789, af2=-sign * 523, tgd1=sign * 345, tgd2=-sign * 234)
            logical, wire = build(values)
            result = subprocess.run([str(executable)], input=logical.hex(), text=True,
                                    capture_output=True, check=True)
            numbers = list(map(float, result.stdout.split()))
            expected = dict(zip(["week", "health", "ura", "aodc", "aode", "toe"] + fields, numbers))
            records.append(dict(rawUnits=values, payloads=wire, expected=expected))
    output = dict(reference="https://github.com/tomojitakasu/RTKLIB/blob/"
                  "71db0ffa0d9735697c6adfd06fdf766d0e5ce807/src/rcvraw.c",
                  method="Synthetic ICD D1 logical fields encoded to BCH/interleaved Android words; "
                  "independent RTKLIB C decode_bds_d1 results. Not captured receiver navigation data.",
                  records=records)
    target = Path(__file__).resolve().parents[1] / "core/src/test/resources/gnss/beidou-d1-reference.json"
    target.write_text(json.dumps(output, indent=2) + "\n")
    print("Generated two independent C D1 field vectors with synthetic wire messages")


if __name__ == "__main__":
    main()
