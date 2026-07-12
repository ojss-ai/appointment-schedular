# /test-gap

Detect untested code paths in recently changed files.

## Steps

1. **Identify changed files**
   - `git diff --name-only HEAD~1 HEAD` (or against the merge base if on a feature branch).
   - Filter to Java and TypeScript source files only (exclude test files themselves).

2. **Map to test files**
   - For each `src/main/java/.../Foo.java` check for `src/test/java/.../FooTest.java`.
   - For each `apps/web/src/.../foo.ts` check for `apps/web/src/.../foo.test.ts` or `foo.spec.ts`.

3. **Coverage gap report**
   - List every source file that has no corresponding test file.
   - For files that have tests, list public methods with no test method referencing them (grep-based heuristic).

4. **Generate stubs**
   - For each gap, generate a skeleton test class/file with `@Test` / `it()` stubs and `// TODO: implement` comments.
   - Place stubs in the correct test directory.

5. **Output**
   - Print: `Test gap scan complete — {N} files missing tests, {N} stub files generated`
