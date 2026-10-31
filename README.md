# Slinky isolated source review

Disposable Windows build and behavioral investigation of a user-supplied client
source archive. This repository is a test workspace, not a safety endorsement.

The manual workflow verifies all 312 reviewed source inputs, builds the Java
payloads, native DLL and launcher, and records their hashes. It then uses signed
[Microsoft Process Monitor](https://learn.microsoft.com/sysinternals/downloads/procmon)
to capture a launcher-only run, followed by an offline Minecraft 1.8.9 startup and
injection attempt if a compatible game window exists. Personal accounts and public
game servers are not used. The scripts refuse ordinary local execution.

See [workflow runs](https://github.com/joystuu/slinky-isolated-review/actions) for
actual outcomes and coverage. A green workflow means the scripted observation
completed; it does not prove injection succeeded, gameplay was tested, or malware
is absent. Native logs and process traces determine the reached execution path.

Three verified ASM build libraries are included with their BSD license under
`ci/vendor`. Client binaries and Minecraft binaries are not distributed here.
See [test configuration and limitations](PROPOSAL.txt).
