# Fixture repository

A hand-built Java 21 Maven project containing exactly 15 classes:
12 production classes and three JUnit test classes. Booking, accounts, and
reporting each contain four production classes and one test class.

Run the fixture's own tests from the IssueExplorer project directory:

```powershell
mvn -f .\src\test\resources\fixture-repo\pom.xml test
```

These are owned fixture sources, not downloaded repository code. They may be
compiled and executed to validate the fixture. Downloaded repositories remain
parse-only and must never be executed by IssueExplorer.

Issue texts and labels are stored alongside this repository in ../fixture-issues.
They describe synthetic investigation scenarios, not confirmed bugs in this fixture.
Use their expected files to check future retrieval behavior. They do not measure
real-world accuracy. Test sources are included to exercise later source/test filtering.
No ranking or parsing pipeline is implemented by this fixture.
