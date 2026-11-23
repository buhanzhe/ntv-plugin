"""Backport Java-7 library references missing on Android 4.0, without java.* shadow classes.

Only constant-pool UTF8 entries change; bytecode, offsets and source artifacts stay intact.
The transformed dependency is a modified jcifs-ng binary (see NOTICE).
"""
import struct
import zipfile

REPLACEMENTS = {
    # BaseConfiguration probes this constant even when jcifs.encoding is set.
    # SMB1 is disabled and SMB2 paths use UTF-16; the legacy OEM fallback can be ASCII.
    b'Cp850': b'US-ASCII',
    b'org/bouncycastle/': b'xiao/bu/channel/files/crypto/bc/',
    b'org.bouncycastle.': b'xiao.bu.channel.files.crypto.bc.',
    b'java/lang/AutoCloseable': b'java/io/Closeable',
    b'java/util/Objects': b'xiao/bu/channel/files/compat/Objects',
    b'java/nio/charset/StandardCharsets': b'xiao/bu/channel/files/compat/StandardCharsets',
}

def class_bytes(data):
    assert data[:4] == b'\xca\xfe\xba\xbe'
    count = struct.unpack_from('>H', data, 8)[0]
    result = bytearray(data[:10])
    pos, index = 10, 1
    widths = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4,
              12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < count:
        tag = data[pos]
        result.append(tag)
        pos += 1
        if tag == 1:
            size = struct.unpack_from('>H', data, pos)[0]
            value = data[pos+2:pos+2+size]
            for old, new in REPLACEMENTS.items():
                value = value.replace(old, new)
            result.extend(struct.pack('>H', len(value)))
            result.extend(value)
            pos += 2 + size
        else:
            size = widths[tag]
            result.extend(data[pos:pos+size])
            pos += size
            if tag in (5, 6): index += 1
        index += 1
    result.extend(data[pos:])
    return bytes(result)

def jar(source, target):
    with zipfile.ZipFile(source) as src, zipfile.ZipFile(target, 'w') as dest:
        for name in sorted(src.namelist()):
            # Signatures no longer apply to a transformed binary. Skip Java-9 variants.
            if name.startswith('META-INF/') and not name.endswith(('.txt', '.html')):
                continue
            data = src.read(name)
            target = name.replace('org/bouncycastle/', 'xiao/bu/channel/files/crypto/bc/')
            dest.writestr(target, class_bytes(data) if name.endswith('.class') else data)
