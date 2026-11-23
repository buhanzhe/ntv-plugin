"""Check the delivered archive, ABI and absence of un-backported runtime types."""
import hashlib
import json
from pathlib import Path
import struct
import zipfile

HERE = Path(__file__).resolve().parents[1]
jar = HERE / 'dist/file-browser.jar'
assert hashlib.sha256(jar.read_bytes()).hexdigest() == jar.with_suffix('.sha256').read_text().strip()
with zipfile.ZipFile(jar) as archive:
    info = json.loads(archive.read('channel-plugin.json'))
    assert info['minSdk'] == 14 and info['api'] == 1 and info['native']
    dex = archive.read('classes.dex')
    count, offset = struct.unpack_from('<II', dex, 64)  # DEX type_ids_size/off
    string_count, string_offset = struct.unpack_from('<II', dex, 56)
    def string(index):
        start = struct.unpack_from('<I', dex, string_offset + index * 4)[0]
        while dex[start] & 128: start += 1
        start += 1
        return dex[start:dex.index(b'\0', start)].decode('utf-8', errors='replace')
    types = {string(struct.unpack_from('<I', dex, offset + i * 4)[0]) for i in range(count)}
    platform = HERE.parents[1] / '.codex-tmp/file-browser/android14.jar'
    with zipfile.ZipFile(platform) as android:
        legacy_types = {'L' + n[:-6] + ';' for n in android.namelist() if n.endswith('.class')}
    # DocumentsContract is deliberately isolated in the SDK-guarded ModernStorage.
    for name in types:
        if name.startswith(('Ljava/', 'Ljavax/', 'Landroid/')):
            assert name in legacy_types or name == 'Landroid/provider/DocumentsContract;', name
    for forbidden in ['Ljava/lang/AutoCloseable;', 'Ljava/util/Objects;', 'Ljava/nio/charset/StandardCharsets;',
                      'Landroid/webkit/WebView;', 'Landroid/app/Activity;']:
        assert forbidden not in types, forbidden
    assert 'Lxiao/bu/channel/files/FileBrowserPlugin;' in types
    assert not any(t.startswith('Lorg/bouncycastle/') for t in types), 'Android system BC package collision'
    assert 'Lxiao/bu/tv/jar/ChannelPlugin;' in types  # host reference only
    # class_defs must not contain a duplicate host contract.
    classes, class_offset = struct.unpack_from('<II', dex, 96)
    for i in range(classes):
        type_index = struct.unpack_from('<I', dex, class_offset + i * 32)[0]
        name = string(struct.unpack_from('<I', dex, offset + type_index * 4)[0])
        assert not name.startswith('Lxiao/bu/tv/jar/'), name
print('PASS artifact hash / API-14 metadata / compatibility references / host ABI ownership')
