# CobbleSynergy 친구용 설치 ZIP 만들기 (CobbleLeague 친구용 설치기와 같은 구조)
# 사용: python tools/package_friends.py <CobbleLeague 친구용 설치 폴더> <배포 jar> <버전>
import hashlib, io, os, shutil, sys, zipfile

src, jar, version = sys.argv[1], sys.argv[2], sys.argv[3]
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
name = "CobbleSynergy-친구용-설치-" + version
out = os.path.join(root, "dist", name)
if os.path.exists(out):
    shutil.rmtree(out)
os.makedirs(os.path.join(out, "payload"))
jar_name = "cobblesynergy-%s.jar" % version
shutil.copy(jar, os.path.join(out, "payload", jar_name))
sha = hashlib.sha256(open(jar, "rb").read()).hexdigest()

ps = io.open(os.path.join(src, "install-client.ps1"), encoding="utf-8-sig").read().replace("\r\n", "\n")
BS = "\\"

def rep(a, b):
    global ps
    assert a in ps, a[:70]
    ps = ps.replace(a, b)

rep("    $teams = @($Mods | Where-Object { $_.Meta -and $_.Meta.id -eq 'cobblemon_quick_teams' })\n",
    "    $league = @($Mods | Where-Object { $_.Meta -and $_.Meta.id -eq 'cobbleleague' })\n")
rep("    if ($teams.Count -ne 1 -or $teams[0].Meta.version -ne '1.5.5') { throw '등록 덱 기능은 Quick Teams 1.5.5 기준입니다. 해당 모드가 들어 있는 COBBLEVERSE 1.7.42 프로필을 선택해 주세요.' }",
    "    if ($league.Count -ne 1 -or @('0.7.1','0.7.2','0.7.3') -notcontains $league[0].Meta.version) { throw 'CobbleSynergy는 CobbleLeague 0.7.1 ~ 0.7.3 애드온입니다. CobbleLeague 친구용 설치를 먼저 설치한 프로필을 선택해 주세요.' }")
rep("Write-Host 'CobbleLeague 0.7.1 · 친구 PC용 설치' -ForegroundColor Cyan",
    "Write-Host 'CobbleSynergy %s · 친구 PC용 설치 (CobbleLeague 0.7.1 ~ 0.7.3 타입 시너지 애드온)' -ForegroundColor Cyan" % version)
rep("'cobbleleague-client-install.json'", "'cobblesynergy-client-install.json'")
rep("        $current = @($mods | Where-Object { $_.Meta -and $_.Meta.id -eq 'cobbleleague' })",
    "        $current = @($mods | Where-Object { $_.Meta -and $_.Meta.id -eq 'cobblesynergy' })")
rep("'다른 CobbleLeague 파일이 추가되어 자동 복원을 중단했습니다.'", "'다른 CobbleSynergy 파일이 추가되어 자동 복원을 중단했습니다.'")
rep("'설치 전 CobbleLeague 상태로 되돌렸습니다. 다른 모드는 유지했습니다.'",
    "'설치 전 상태로 되돌렸습니다 (CobbleSynergy 제거 또는 이전 버전 복구). 다른 모드는 유지했습니다.'")
rep("if ($payloadMeta.id -ne 'cobbleleague' -or", "if ($payloadMeta.id -ne 'cobblesynergy' -or")
rep("    $oldMods = @($mods | Where-Object { $_.Meta -and $_.Meta.id -eq 'cobbleleague' })",
    "    $oldMods = @($mods | Where-Object { $_.Meta -and $_.Meta.id -eq 'cobblesynergy' })")
rep("cobbleleague-installer-backups", "cobblesynergy-installer-backups")
rep("('.cobbleleague-'", "('.cobblesynergy-'")
rep("version='0.7.1'; backup=$backupRelative", "version=$manifest.clientVersion; backup=$backupRelative")
rep("    Write-Host '새 리그 대전은 서버에도 같은 애드온이 설치되어 있어야 나타납니다.'",
    "    Write-Host '시너지는 서버(방장 월드)에도 같은 CobbleSynergy가 설치되어 있어야 적용됩니다.'\n"
    "    Write-Host 'U 키: 시너지 도감 / F4 던전 메뉴 오른쪽 위 [시너지] 버튼: 현재 효과와 켜기·끄기'")
assert "cobbleleague-" not in ps.replace("cobbleleague'", "")
io.open(os.path.join(out, "install-client.ps1"), "w", encoding="utf-8-sig", newline="\r\n").write(ps)

for target, action in [("설치.bat", "Install"), ("INSTALL.bat", "Install"), ("되돌리기.bat", "Restore"), ("RESTORE.bat", "Restore")]:
    bat = io.open(os.path.join(src, "RESTORE.bat"), encoding="ascii").read().replace("\r\n", "\n")
    bat = bat.replace("CobbleLeague Friends Installer", "CobbleSynergy Friends Installer").replace("-Action Restore", "-Action " + action)
    io.open(os.path.join(out, target), "w", encoding="ascii", newline="\r\n").write(bat)

pkg = ('{\n  "name": "CobbleSynergy friends installer",\n  "version": "%s",\n  "clientVersion": "%s",\n'
       '  "requiresCobbleLeague": "0.7.1 ~ 0.7.3",\n  "payload": "payload/%s",\n  "sha256": "%s"\n}\n') % (version, version, jar_name, sha)
io.open(os.path.join(out, "package.json"), "w", encoding="utf-8", newline="\r\n").write(pkg)
guide = io.open(os.path.join(root, "tools", "사용방법.txt"), encoding="utf-8-sig").read().replace("\r\n", "\n")
io.open(os.path.join(out, "사용방법.txt"), "w", encoding="utf-8-sig", newline="\r\n").write(guide)

for extra in ["서버장에게.txt"]:
    t = io.open(os.path.join(root, "tools", extra), encoding="utf-8-sig").read().replace(chr(13) + chr(10), chr(10))
    io.open(os.path.join(out, extra), "w", encoding="utf-8-sig", newline=chr(13) + chr(10)).write(t)

zpath = os.path.join(root, "dist", name + ".zip")
if os.path.exists(zpath):
    os.remove(zpath)
with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
    for base, _, files in os.walk(out):
        for f in files:
            full = os.path.join(base, f)
            z.write(full, os.path.join(name, os.path.relpath(full, out)))
print(zpath, sha)
