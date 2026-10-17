#!/usr/bin/env python3
"""Rewrite DT_NEEDED entries inside an ELF binary, in place.

Why this exists
---------------
Android's APK native-lib extraction (jniLibs) only unpacks files named
``lib*.so``. Termux builds of proot declare ``DT_NEEDED libtalloc.so.2`` and
``libandroid-shmem.so``, which can never be extracted under those literal
names, so the bionic linker fails with:

    CANNOT LINK EXECUTABLE ".../libproot.so": library "libtalloc.so.2" not found

We ship the libraries as ``libtalloc.so`` / ``libshmem.so`` and rewrite proot's
DT_NEEDED strings to match. A replacement is only accepted when it is strictly
shorter than (or equal to) the original string, so it can be written over the
old bytes and NUL-padded: every ELF offset/size stays valid, no structural
edit — the operation is provably safe.

Usage:
    python3 patch_elf_needed.py <elf> <old-name>=<new-name> [<old>=<new> ...]
    python3 patch_elf_needed.py --verify <elf> [<old-name> ...]

Exit codes: 0 ok, 1 usage/IO error, 2 patch refused (string not shorter),
3 verification failed (name still present as DT_NEEDED).
"""
import struct
import sys


def _sections(data: bytes):
    e_shoff = struct.unpack_from('<Q', data, 0x28)[0]
    e_shentsize = struct.unpack_from('<H', data, 0x3A)[0]
    e_shnum = struct.unpack_from('<H', data, 0x3C)[0]
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        sh_type = struct.unpack_from('<I', data, off + 4)[0]
        sh_offset = struct.unpack_from('<Q', data, off + 24)[0]
        sh_size = struct.unpack_from('<Q', data, off + 32)[0]
        sh_link = struct.unpack_from('<I', data, off + 40)[0]
        sh_entsize = struct.unpack_from('<Q', data, off + 56)[0]
        yield sh_type, sh_offset, sh_size, sh_link, sh_entsize


def _dynamic_and_strtab(data: bytes):
    dyn = None
    for sh_type, sh_offset, sh_size, _sh_link, sh_entsize in _sections(data):
        if sh_type == 6:  # SHT_DYNAMIC
            dyn = (sh_offset, sh_size, sh_entsize)
    if dyn is None:
        raise ValueError('SHT_DYNAMIC section not found')
    sh_offset, sh_size, sh_entsize = dyn
    # resolve the string table through DT_STRTAB (absolute vaddr), then
    # translate vaddr -> file offset via PT_LOAD segments. This is robust even
    # if section header string-table indices would be ambiguous.
    str_vaddr = None
    for j in range(sh_size // sh_entsize):
        tag, val = struct.unpack_from('<QQ', data, sh_offset + j * sh_entsize)
        if tag == 5:  # DT_STRTAB
            str_vaddr = val
            break
    if str_vaddr is None:
        raise ValueError('DT_STRTAB not found')
    e_phoff = struct.unpack_from('<Q', data, 0x20)[0]
    e_phentsize = struct.unpack_from('<H', data, 0x36)[0]
    e_phnum = struct.unpack_from('<H', data, 0x38)[0]
    strtab_off = None
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type = struct.unpack_from('<I', data, off)[0]
        p_offset, p_vaddr = struct.unpack_from('<QQ', data, off + 8)
        p_filesz = struct.unpack_from('<Q', data, off + 32)[0]
        if p_type == 1 and p_vaddr <= str_vaddr < p_vaddr + p_filesz:
            strtab_off = p_offset + (str_vaddr - p_vaddr)
            break
    if strtab_off is None:
        raise ValueError('DT_STRTAB vaddr not inside any PT_LOAD segment')
    return dyn, strtab_off


def needed_names(data: bytes):
    (sh_offset, sh_size, sh_entsize), strtab_off = _dynamic_and_strtab(data)
    out = []
    for j in range(sh_size // sh_entsize):
        tag, val = struct.unpack_from('<QQ', data, sh_offset + j * sh_entsize)
        if tag == 1:  # DT_NEEDED
            end = data.index(b'\0', strtab_off + val)
            out.append((val, data[strtab_off + val:end].decode()))
        if tag == 0:
            break
    return out


def patch(path: str, replacements: dict) -> int:
    with open(path, 'rb') as f:
        data = bytearray(f.read())
    entries = needed_names(bytes(data))
    strtab_off = _dynamic_and_strtab(bytes(data))[1]
    changed = 0
    for val, name in entries:
        new = replacements.get(name)
        if new is None or new == name:
            continue
        if len(new) > len(name):
            print(f'REFUSED: {name!r} -> {new!r} is longer; in-place edit unsafe')
            return 2
        start = strtab_off + val
        data[start:start + len(name)] = new.encode() + b'\0' * (len(name) - len(new))
        print(f'patched DT_NEEDED: {name} -> {new} (NUL-padded, {len(name) - len(new)} byte(s) slack)')
        changed += 1
    if changed:
        with open(path, 'wb') as f:
            f.write(data)
    plural = 'y' if changed == 1 else 'ies'
    print(f'{path}: {changed} DT_NEEDED entr{plural} patched')
    return 0


def verify(path: str, forbidden: list) -> int:
    with open(path, 'rb') as f:
        data = f.read()
    names = [n for _, n in needed_names(data)]
    bad = [n for n in forbidden if n in names]
    print(f'{path}: DT_NEEDED = {names}')
    if bad:
        print(f'VERIFY FAILED: still needed: {bad}')
        return 3
    return 0


def main() -> int:
    args = sys.argv[1:]
    if len(args) < 2:
        print(__doc__)
        return 1
    if args[0] == '--verify':
        return verify(args[1], args[2:])
    path = args[0]
    try:
        replacements = dict(a.split('=', 1) for a in args[1:])
    except ValueError:
        print('bad replacement spec, expected old=new')
        return 1
    return patch(path, replacements)


if __name__ == '__main__':
    sys.exit(main())
