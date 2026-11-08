from pathlib import Path
import re,zipfile,json,hashlib,shutil
root=Path(__file__).resolve().parents[1]
props=dict(l.split('=',1) for l in (root/'gradle.properties').read_text().splitlines() if '=' in l and not l.startswith('#'))
version=props['mod_version'];jar=root/'build/libs'/f'cobblesynergy-{version}.jar'
with zipfile.ZipFile(jar) as z:
    meta=json.loads(z.read('fabric.mod.json'));assert z.read('cobblesynergy/build.properties').decode().strip()=='test=false'
out=root/'dist';out.mkdir(exist_ok=True);shutil.copy2(jar,out/jar.name)
manifest=dict(id='cobblesynergy',version=version,requiresCobbleLeague=meta['depends']['cobbleleague'],filename=jar.name,sha256=hashlib.sha256(jar.read_bytes()).hexdigest())
(out/'synergy-release.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
print('Standalone addon:',out/jar.name)
print('Player auto-update bundle: https://github.com/arscabar/CobbleLeague/releases')
