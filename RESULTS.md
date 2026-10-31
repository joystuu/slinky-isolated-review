# Initial isolated runtime findings — 2026-10-31

The reviewed source built successfully, and the rebuilt client initialized inside
Minecraft 1.8.9 on a disposable GitHub-hosted Windows VM. No obvious malicious
behavior was identified in the captured startup/menu observation. This is limited
negative evidence, not a guarantee that the client is safe on a personal PC.

## Evidence

Tested commit: `e5e29069fb009958958ca7f934c0c43d95b47536`.
[Successful injection run](https://github.com/joystuu/slinky-isolated-review/actions/runs/41226604295).
All 312 reviewed source inputs matched their original SHA256 manifest before
building all three Java payload JARs, the native DLL, and the launcher. No supplied
client binaries were used. The client source was not edited.

The native log records:

- The DLL loaded into the game process and detected vanilla 1.8.9 official names.
- The official payload was added to the game class loader.
- 14 classes patched, zero failures and zero warnings.
- 66 modules initialized; final native status `RUNNING`; launcher exit code 0.

Process Monitor independently recorded `SlinkyNative.dll` loading in the JVM.
A screenshot showed the game still at its main menu after injection. Observation
continued for approximately 35 seconds after initialization, then the test harness
terminated the game. This was not a gameplay or module-feature test.

## Observed activity

The successful run captured 1,473 launcher-only events and 49,934 events covering
the game baseline, injection, and subsequent menu observation. The latter contained
48,527 JVM events, 247 launcher events, and 1,160 console-host events.

Observed client-related writes were the extracted DLL/JAR, native log, and
`.slinky/slinky-semantic.json` plus its `.active` companion. Registry value writes
after native loading were Windows BAM activity records; filesystem journal writes
were also present. The only captured child-process creation was the launcher
starting Windows `Conhost.exe`.

No TCP/UDP events were captured. No browser-cookie/login database, wallet, Discord,
Windows SAM hive, Run/RunOnce startup, startup-folder, hosts-file, or Defender-service
path access was identified by the targeted trace checks. A broader security-word
search matched normal Java security/trust-store accesses and Windows WMI/folder
configuration queries; those were not evidence of theft or security disabling.

## Exact tested artifact hashes

| Artifact | SHA256 |
|---|---|
| payload-101.jar | `1F5C8CB19607FB1760B8EBC353D691CDD35F4603078B25AA2388CE91322CD49D` |
| payload-102.jar | `CAB052FABE2DCC89F55EF0896E3FABD0508032603722BAB42BB00AB197802432` |
| payload-103.jar | `19110261450A01C8D516759D7DCF9FA4282A2E404D348CD1F5797B6B46EC60C9` |
| SlinkyNative.dll | `88F3C0958B4BA0235FAE5458CAAF99177A7193DC375D999E2990FF01BA670455` |
| Slinky.exe | `57FF251918025C17071D880A1A7906B55702EDF98C5F77C2606249851CF0EE80` |

These identify the actual tested outputs. Separate rebuilds produced different
hashes; byte-for-byte reproducibility has not been established. Binaries were not
downloaded to the personal PC or retained as Actions artifacts.

## Environment and limitations

Windows Server 2022, JDK 17 build tools, JDK 8 game runtime; official Minecraft
metadata/native libraries and 722 assets were hash-verified. The VM's default
OpenGL 1.1 caused vanilla Minecraft to crash before injection in
[the prior run](https://github.com/joystuu/slinky-isolated-review/actions/runs/41226346412).
The successful run used third-party Mesa Windows distribution 26.2.5, pinned to
the [publisher's release](https://github.com/pal1000/mesa-dist-win/releases/tag/26.2.5)
SHA256 `997123ef4ce8896204ab912d5b54f19372c487ff9835bcefafa2fca23b8953c2`.
That hash establishes package identity, not a separate audit of the renderer.
The VM lacked working OpenAL audio, but the menu and injection initialized.

Per-program firewall rules blocked launcher/JVM networking. Offline silence does
not establish what the client would do online. The rules were not a global denial
of all guest networking. No personal account, public server, loaded world, feature
activation, delayed trigger, restart/persistence test, or unload/reinjection cycle
was exercised. Existing source concerns about process selection, predictable
temporary paths, partial transformations, and retained modifications remain.

Process Monitor is not comprehensive EDR. Filtering preserves the selected process
trees, not every guest process, and trace data is produced within the guest. An
adversarial program could evade or tamper with observation. A green workflow means
the scripted observation completed, not that malware absence was proven.

The complete filtered traces are gzip/base64 encoded in ordinary job logs. Use
`python ci/Decode-Evidence.py downloaded-run.log output-folder` to decode data-only
JSON evidence and the screenshot. Full-system PML is ephemeral. No Actions artifact
or cache uploads were used. All client execution occurred in the disposable VM.
