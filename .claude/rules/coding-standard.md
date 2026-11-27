# Java Coding Standards

Same Huawei CodeArts Check rule set as agent-core-java: 124 base rules
(`G.*`) + extensions (`X.*`). Security rules (marked `[S]`) carry higher
risk — prioritize them. The full rule text with OK/BAD examples lives in
agent-core-java `.claude/skills/coding-standard-full/` (same standard,
both repos); this file stays the quick reference for runtime work.

## Quick Self-Check (covers 80% of common issues)

1. `G.NAM.03` Class/enum/interface: PascalCase
2. `G.NAM.04` Method names: camelCase
3. `G.NAM.05` Constants: ALL_UPPERCASE with underscores
4. `G.ERR.01` No empty catch blocks
5. `G.ERR.02` No catching Throwable/Exception/RuntimeException directly
6. `G.ERR.06` [S] Pass original exception as cause when re-throwing
7. `G.FMT.07` Avoid empty blocks; add comment when necessary
8. `G.LOG.01` Use SLF4J facade, not Log4j/Logback directly
9. `G.LOG.02` Logger: private static final
10. `G.OBJ.06` Override hashCode when overriding equals
11. `G.PRM.05` No unnecessary objects
12. `G.CON.07` New threads must specify a name

## Runtime-Specific Priorities (CodeCheck/CI-Bot recurring findings)

- **X.CON.06**: no `Executors` convenience factories — explicit
  `ThreadPoolExecutor` with bounded queue + named threads (see
  concurrency.md for the repo's reference patterns).
- **Literal `return null`** trips CodeCheck's null-return check —
  return an accumulated variable or `Optional` instead (see GitCode
  !262 `parseId` precedent: `cd34430c`).
- **Unused imports** — CodeCheck warnings; clean before pushing.
- **G.LOG.02/G.LOG.01**: SLF4J `private static final` directly; this
  repo does not use Core's `Loggers` facade (deliberate difference).
- Test naming `CUT#method_scenario_expected` + Given/When/Then
  (X.TST.05/06) — reviewers and CI-Bot check both.

## Scenario → Rules

| Scenario | Rules |
|---|---|
| Spring beans / auto-config | `G.OBJ.01-08`, `G.NAM.03/04`, `G.CMT.01/03` |
| Exception handling | `G.ERR.01-09`, transport mapping per error-codes-standard.md |
| Multi-threaded code | `G.CON.01/05-12`, `X.CON.01-06`, concurrency.md |
| Logging | `G.LOG.01-04`, logging.md |
| Collections/generics | `G.COL.02-04`, `X.COL.01-04` |
| Method signatures | `G.MET.01-07` (`≤5 params` — use a params record for more) |
| Constants/enums | `G.NAM.05`, `G.DCL.04/05` |
| Tests | `X.TST.01-06`, test-flow.md |
| Code Review | Self-Check list first, then scenario table |

## All Rules (by category — see coding-standard-full for text)

### G.CMT Comments — `01` Public/protected must have Javadoc · `02` Top-level class Javadoc: description+date/version · `03` Method Javadoc: description+@param/@return/@throws · `04` No empty formatted method headers · `05` File header: copyright/license · `06` Blank line between comments and code · `07` No TODO/FIXME in production code

### G.COL Collections — `02` Prefer generic collections over arrays · `03` Bounded generics · `04` No remove/add inside foreach

### G.CON Concurrency — `01` [S] Avoid synchronization pitfalls · `05` [S] No overriding thread-safe with non-thread-safe · `06` Concurrency utils, not wait/notify · `07` New threads named · `08` setUncaughtExceptionHandler · `09` No relying on scheduler/priority/yield · `10` Handle interruption cooperatively · `11` [S] No Thread.stop() · `12` Thread pools, not uncontrolled threads

### G.CTL Control Flow — `01` No assignments in conditions · `02` else-if chains need else · `03` switch needs default · `05` No modifying loop variable in body

### G.DCL Declarations — `01` One variable per line · `02` Declare close to first use · `03` No C-style arrays · `04` No ordinal() ordering · `05` No mutable constants

### G.ERR Exceptions — `01` No empty catch · `02` No Throwable/Exception/RuntimeException catch · `03` No pre-checkable RuntimeException catches · `04` [S] No sensitive info in exceptions · `05` Exceptions match abstraction level · `06` [S] Original as cause when re-throwing · `07` Max 5 exceptions per method · `08` [S] Nothing in finally that exits · `09` [S] No System.exit() · `10` No SuppressWarning at class level · `11` Log GeneralSecurityException

### G.FIO File IO — `01` [S] Validate+normalize external paths · `02` [S] Security-check zip entries · `03` [S] int returns for single-byte reads · `04` [S] Prevent process blocking on IO

### G.FMT Formatting — `01` UTF-8 · `02` copyright/package/import/class structure · `03` Import order · `04` Member order · `05` Braces required · `06` Left brace end-of-line · `07` No empty blocks · `08` 4-space indent · `09` One statement per line · `10` Line width ≤120 · `11` Line break before operator · `12` Reduce blank lines · `13` Keyword spacing · `14` No alignment spaces · `15` Enum comma-separated · `16` Fall-through commented · `17` Annotation own line · `18` Comment indent matches · `19` JLS modifier order · `20` Long with L suffix

### G.LOG Logging — `01` SLF4J facade · `02` Logger private static final · `03` Leveled · `04` English logs

### G.MET Methods — `01` Short, ≤5 params · `03` No params as temp vars · `04` Varargs with caution · `05` Empty collection, not null · `06` Optional over null · `07` [S] Don't ignore return values

### G.NAM Naming — `01` ≤64 chars · `02` lowercase packages · `03` PascalCase types · `04` camelCase methods · `05` UPPER constants · `06` camelCase vars · `07` No negative boolean names · `08` Booleans start with is/has/can

### G.OBJ Classes — `01` No public non-final fields · `02` No overridable calls in constructor · `03` Reuse constructors · `04` No name shadowing · `05` No primitive/wrapper same-name overloads · `06` hashCode with equals · `07` @Override required · `08` Singleton correctly · `09` [S] Class name for static calls · `10` No redundant interface modifiers

### G.OTH Other — `01` [S] Secure random in security contexts · `02` [S] SSLSocket · `03` Delete unused code/imports · `04` [S] No public network addresses · `05` [S] Remove dead code

### G.PRM Performance — `01` toArray(T[]) · `02` arraycopy/copyOf · `04` Pre-compile regex · `05` No unnecessary objects · `07` try-with-resources · `08` No explicit GC · `09` No finalizers · `10` No temp vars for return

### G.SER Serialization — `01` Avoid Serializable · `02` Explicit serialVersionUID · `04` No system resources serialized · `05` [S] No non-static inner classes · `07` [S] Deserialization security

### G.TYP Types — `03` No float loop counters · `04` BigDecimal for exact math · `05` No float ==, no wrapper equals · `06` isNaN() · `07` No hardcoded separators · `08` Locale.ROOT/ENGLISH · `09` Explicit char↔byte encoding · `11` Prefer primitives · `12` Explicit conversions · `13` instanceof before downcast

### X.TST Testing — `01` No shared test state · `02` Assert business logic · `03` No static mocks, prefer injectable · `04` Tag integration tests · `05` CUT#method_scenario_expected · `06` Given/When/Then

### X.CON Concurrency — `01` DateTimeFormatter over SimpleDateFormat · `02` DCL volatile · `03` No locking String/Integer · `04` computeIfAbsent · `05` ConcurrentHashMap · `06` ThreadPoolExecutor only

### X.COL Collections — `01` asList fixed-size · `02` subList is view · `03` isEmpty() · `04` No LinkedList random access

### X.PRM Performance — `01` No DB/RPC in loops · `02` StringBuilder in loops · `03` No multi-traverse Stream · `04` Pattern.compile once · `05` No large objects in hot paths

### X.DEP Dependencies — `01` Versions in parent dependencyManagement · `02` No large libs for one method · `03` provided only for container deps · `04` No circular deps
