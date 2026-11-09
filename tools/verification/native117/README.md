# Power 1.17 Eclipse compiler check

This check compiles the pinned Power candidate with Eclipse's compiler, then
runs the complete production unit-test inventory from that fresh output. It
does not run Gradle, start Minecraft, or replace any built mod jar.

Run it after the normal release build has generated `.classpath`, processed
resources and the 201-test Gradle inventory:

```powershell
& ./tools/verification/native117/Verify-PowerEcj117.ps1 `
  -Evidence 'D:/PowerAdvantage/_verification/advantage-117-port-20261031/Power ECJ new receipt'
```

Choose a new evidence directory outside the checkout. Existing evidence is
never overwritten. The command accepts explicit compiler and Java installation
paths; its defaults identify the locally qualified installations. The compiler
jar has a required SHA-256 pin.

## What it proves

- Production, test and Forge test-bootstrap sources match basePower commit
  `a6fc3050b0e92e89abf49bf18bbbfeb87fb3a1eb`.
- Libraries come from the actual `.classpath`, with the declared main/test
  scopes and the exact local Base Metals and released OreSpawn hashes checked.
- ECJ runs on Java 17 against the Java 16 module image. Forbidden and discouraged
  references remain errors; a restricted-JDK negative control must fail.
- Main, test, reporter and capability-bootstrap classes are freshly compiled
  into the evidence directory. Every emitted class is checked for Java 16
  bytecode. No existing Power classes or Power jar can satisfy compilation.
- All 201 tests run on Java 16 with the freshly built Forge capability agent.
  Structured JUnit evidence must match every baseline test identity, with no
  failures, ignored tests, assumptions, duplicate starts or unfinished tests.
  Separate ignored-test and assumption controls must be rejected.
- Input hashes and the protected Gradle/Eclipse output inventory are checked
  again before success is reported.

The report is `ecj-results.json`; compiler XML, compiler logs, argument files,
fresh classes and JUnit XML remain beside it. Warnings remain visible. A failed
check keeps its report and output rather than replacing them on a retry.

The Java 8 `../Verify-EclipseCompilation.ps1` belongs to historical ports and is
not used by this native 1.17 check. This is compiler and worldless unit-test
evidence, not gameplay, migration or visual acceptance.
