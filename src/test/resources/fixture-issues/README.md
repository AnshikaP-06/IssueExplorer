# Fixture issues

expected-relevant-files.json contains an array of four records:

- id: unique fixture identifier.
- category: explicit-class, stack-trace, vague, or no-match.
- issueFile: text filename relative to this directory.
- relevantFiles: expected investigation paths relative to ../fixture-repo.

The explicit-class case names BookingService directly. The stack-trace case
references an actual AccountService.authenticate source line. The vague case
uses business language without class names. The no-match case concerns GPS
navigation, a capability absent from the repository, and has no relevant files.

These are deterministic synthetic navigation scenarios. The labels identify
places to investigate, not proven faulty files, and are not a real-world evaluation
or a held-out dataset. Ranking code will be added only in a later requested step.
