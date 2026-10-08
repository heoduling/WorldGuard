from pathlib import Path
import argparse, hashlib, io, shutil, subprocess, tempfile, zipfile

EXPECTED = 'aff6e1f1033f87e44c5b49fb77f44d6ac694dc388ec114ce3f72259211ba6b77'
parser = argparse.ArgumentParser()
parser.add_argument('--core', type=Path, required=True)
parser.add_argument('--libraries', type=Path, required=True)
parser.add_argument('--asm', type=Path, required=True)
parser.add_argument('--asm-tree', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--work', type=Path, default=Path(tempfile.gettempdir()) / 'worldguard-core-bridge-build')
parser.add_argument('--java', type=Path, default=Path('D:/java/bin'))
args = parser.parse_args()
raw = args.core.read_bytes()
assert hashlib.sha256(raw).hexdigest() == EXPECTED, 'Refusing an unaudited core build'
work = args.work.resolve()
work.mkdir(parents=True, exist_ok=True)
classes = work / 'classes'
classes.mkdir(exist_ok=True)
inner_path = 'META-INF/versions/26.2/shiroha-26.2.jar'
entity_path = 'net/minecraft/world/entity/Entity.class'
queue_path = 'net/minecraft/world/entity/Entity$QueuedAsyncTeleport.class'
with zipfile.ZipFile(io.BytesIO(raw)) as outer:
    inner_raw = outer.read(inner_path)
    old_hash = hashlib.sha256(inner_raw).hexdigest()
    assert old_hash == outer.read('META-INF/versions.list').decode().split()[0]
inner_file = work / 'original-server.jar'
inner_file.write_bytes(inner_raw)
with zipfile.ZipFile(io.BytesIO(inner_raw)) as inner:
    (work / 'Entity.original.class').write_bytes(inner.read(entity_path))
    (work / 'Queue.original.class').write_bytes(inner.read(queue_path))
libraries = sorted(p.resolve() for p in args.libraries.rglob('*.jar') if 'local-SNAPSHOT' not in str(p) and '26.2.local' not in str(p))
local_libraries = work / 'libraries'
local_libraries.mkdir(exist_ok=True)
copied = []
for index, path in enumerate([args.asm.resolve(), args.asm_tree.resolve()] + libraries):
    local = local_libraries / (str(index) + '.jar')
    shutil.copy2(path, local)
    copied.append(local)
classpath = ';'.join(map(str, [inner_file] + copied))
sources = sorted((Path(__file__).parent / 'src').rglob('*.java'))
def run_argfile(program, arguments, name):
    argfile = work / name
    argfile.write_text('\n'.join('"' + str(a).replace('\\', '/') + '"' for a in arguments), encoding='utf-8')
    subprocess.run([str(args.java / program), '@' + str(argfile)], check=True)
run_argfile('javac.exe', ['-encoding','UTF-8','-classpath',classpath,'-d',classes] + sources, 'compile.args')
run_argfile('java.exe', ['-classpath',str(classes)+';'+classpath,'PatchTeleport',work/'Entity.original.class',work/'Entity.patched.class',work/'Queue.original.class',work/'Queue.patched.class'], 'patch.args')
new_inner = io.BytesIO()
with zipfile.ZipFile(io.BytesIO(inner_raw)) as original, zipfile.ZipFile(new_inner,'w',zipfile.ZIP_DEFLATED) as patched:
    for entry in original.infolist():
        replacements = {entity_path: work/'Entity.patched.class', queue_path: work/'Queue.patched.class'}
        patched.writestr(entry, replacements[entry.filename].read_bytes() if entry.filename in replacements else original.read(entry.filename))
    for path in sorted((classes/'wgbridge').glob('*.class')):
        patched.write(path, 'wgbridge/'+path.name)
    patched.writestr('worldguard-teleport-bridge.properties', 'revision=1\noriginal-bundler-sha256='+EXPECTED+'\n')
new_hash = hashlib.sha256(new_inner.getvalue()).hexdigest()
with zipfile.ZipFile(io.BytesIO(raw)) as original, zipfile.ZipFile(args.output,'w',zipfile.ZIP_DEFLATED) as patched:
    for entry in original.infolist():
        data = original.read(entry.filename)
        if entry.filename == inner_path: data = new_inner.getvalue()
        elif entry.filename == 'META-INF/versions.list': data = data.replace(old_hash.encode(),new_hash.encode())
        patched.writestr(entry,data)
with zipfile.ZipFile(args.output) as result:
    assert result.testzip() is None
    with zipfile.ZipFile(io.BytesIO(result.read(inner_path))) as inner:
        assert inner.testzip() is None
        before = zipfile.ZipFile(io.BytesIO(inner_raw))
        changed = [n for n in before.namelist() if before.read(n) != inner.read(n)]
        assert set(changed) == {entity_path, queue_path}, changed
    with zipfile.ZipFile(io.BytesIO(raw)) as before:
        assert {n for n in before.namelist() if before.read(n) != result.read(n)} == {inner_path, 'META-INF/versions.list'}
print('CORE_BRIDGE_PASS changed_existing_classes=2 added_classes=2 sha256='+hashlib.sha256(args.output.read_bytes()).hexdigest())
