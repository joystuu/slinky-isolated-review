import pathlib, struct, json, hashlib, zipfile, sys, io

def sha(b):
    return hashlib.sha256(b).hexdigest()

def pe_resources(data):

    def u16(o):
        return struct.unpack_from('<H', data, o)[0]

    def u32(o):
        return struct.unpack_from('<I', data, o)[0]
    assert data[:2] == b'MZ'
    p = u32(60)
    assert data[p:p + 4] == b'PE\x00\x00'
    machine = u16(p + 4)
    count = u16(p + 6)
    size = u16(p + 20)
    opt = p + 24
    assert u16(opt) == 523 and machine == 34404, 'Expected AMD64 PE32+'
    sections = []
    for i in range(count):
        s = opt + size + i * 40
        sections.append((u32(s + 12), max(u32(s + 8), u32(s + 16)), u32(s + 20)))

    def offset(rva):
        for start, length, raw in sections:
            if start <= rva < start + length:
                return raw + rva - start
        raise ValueError('RVA outside image')
    rva = u32(opt + 112 + 2 * 8)
    base = offset(rva)
    out = {}

    def walk(relative, path):
        at = base + relative
        length = u16(at + 12) + u16(at + 14)
        for i in range(length):
            ident, target = struct.unpack_from('<II', data, at + 16 + i * 8)
            if ident & 2147483648:
                loc = base + (ident & 2147483647)
                units = u16(loc)
                key = data[loc + 2:loc + 2 + units * 2].decode('utf-16le')
            else:
                key = ident
            if target & 2147483648:
                walk(target & 2147483647, path + (key,))
            else:
                loc = base + target
                start = offset(u32(loc))
                length = u32(loc + 4)
                blob = data[start:start + length]
                assert len(blob) == length
                out[path + (key,)] = blob
    walk(0, ())
    return out

def find_resource(resources, kind, ident):
    matches = [v for k, v in resources.items() if k[:2] == (kind, ident)]
    assert len(matches) == 1, (kind, ident)
    return matches[0]
