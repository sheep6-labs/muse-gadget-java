/** Tiny check helper (no JUnit): counts passes, prints failures, exits non-zero from {@link #done()}. */
final class T {
    static int pass, fail;

    static void check(String name, boolean ok) { check(name, ok, ""); }

    static void check(String name, boolean ok, String detail) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name + (detail.isEmpty() ? "" : "  [" + detail + "]"));
    }

    static void eq(String name, String expected, String actual) {
        check(name, expected.equals(actual), expected.equals(actual) ? "" : "expected=" + expected + " actual=" + actual);
    }

    static void done() {
        System.out.println((fail == 0 ? "ALL PASS" : "FAILED") + " (" + pass + " passed, " + fail + " failed)");
        if (fail != 0) System.exit(1);
    }
}
