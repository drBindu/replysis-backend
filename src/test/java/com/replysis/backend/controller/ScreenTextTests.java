package com.replysis.backend.controller;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A screen answer streams its spoken part the moment it exists and holds back only the code, and a slow
 * connection can send the words read off the screen instead of a picture. These cover the two pure pieces:
 * where the code section starts, and how several views of one page become one text.
 */
class ScreenTextTests {

    @Test
    void codeSectionStartsAtTheDetailHeading() {
        String answer = "SAY THIS\nI would use a hash map.\n\nDETAIL\n```python\nx = 1\n```";
        assertEquals(answer.indexOf("DETAIL"), InterviewController.codeSectionStart(answer));
    }

    @Test
    void codeSectionStartsAtTheFirstFenceWhenThereIsNoHeading() {
        String answer = "SAY THIS\nUse a loop.\n\n```java\nint x;\n```";
        assertEquals(answer.indexOf("```"), InterviewController.codeSectionStart(answer));
    }

    @Test
    void anIndentedFenceStillCounts() {
        String answer = "Use a loop.\n   ```java\nint x;";
        assertEquals(answer.indexOf("   ```"), InterviewController.codeSectionStart(answer));
    }

    @Test
    void theSpokenPartAloneHasNoCodeSection() {
        assertEquals(-1, InterviewController.codeSectionStart("SAY THIS\nThe time is O(n), the space is O(1)."));
    }

    @Test
    void theWordDetailInsideASentenceIsNotTheHeading() {
        assertEquals(-1, InterviewController.codeSectionStart("SAY THIS\nI will go into more DETAIL if you like."));
        assertEquals(-1, InterviewController.codeSectionStart("SAY THIS\nThe DETAILS are simple."));
    }

    @Test
    void aHeadingOrFenceStillArrivingIsNotFoundYet() {
        // The stream holds back a few characters for exactly this: a marker split across two tokens.
        assertEquals(-1, InterviewController.codeSectionStart("SAY THIS\nUse a loop.\n\nDET"));
        assertEquals(-1, InterviewController.codeSectionStart("SAY THIS\nUse a loop.\n\n``"));
    }

    @Test
    void oneViewIsJustItsText() {
        assertEquals("Two Sum\nGiven an array", InterviewController.joinScreenTexts(List.of("Two Sum\nGiven an array\n")));
    }

    @Test
    void aScrolledPageIsJoinedWithoutRepeatingTheOverlap() {
        String top = "1. Two Sum\nGiven an array of integers nums\nExample 1:\nInput: nums = [2,7]";
        String bottom = "Example 1:\nInput: nums = [2,7]\nConstraints:\n2 <= nums.length <= 10^4";
        String joined = InterviewController.joinScreenTexts(List.of(top, bottom));

        assertEquals(1, joined.split("Example 1:", -1).length - 1, "the overlapping lines appear once");
        assertTrue(joined.indexOf("Given an array") < joined.indexOf("Constraints:"), "oldest view first");
        assertTrue(joined.contains("2 <= nums.length <= 10^4"));
    }

    @Test
    void aLineThatRepeatsFurtherDownIsKept() {
        // Only the run at the start of a view is the overlap; a repeated "pass" in the middle of code is real.
        String top = "def f():\n    pass";
        String bottom = "def f():\n    pass\ndef g():\n    pass";
        String joined = InterviewController.joinScreenTexts(List.of(top, bottom));
        assertEquals(2, joined.split("pass", -1).length - 1, "the first view's pass and the second view's own pass");
        assertTrue(joined.contains("def g():"));
    }

    @Test
    void emptyViewsAreIgnored() {
        assertEquals("only", InterviewController.joinScreenTexts(java.util.Arrays.asList(null, "  ", "only")));
        assertEquals("", InterviewController.joinScreenTexts(List.of()));
    }

    @Test
    void aConnectionTestIsRecognisedAndAScreenshotIsNot() {
        assertTrue(InterviewController.isProbe(java.util.Map.of("probe", true)));
        assertTrue(InterviewController.isProbe(new java.util.HashMap<>(java.util.Map.of("probe", "x", "image", "AAAA"))));
        assertFalse(InterviewController.isProbe(java.util.Map.of("image", "AAAA")));
        assertFalse(InterviewController.isProbe(java.util.Map.of("text", "some words")));
        assertFalse(InterviewController.isProbe(null));
    }

    @Test
    void onlyMacOneZeroTwoFiftyAndNewerGetsA204ForItsConnectionTest() {
        var accept = new java.util.function.Predicate<com.replysis.backend.security.AppInfo>() {
            public boolean test(com.replysis.backend.security.AppInfo a) { return InterviewController.acceptsProbe204(a); }
        };
        assertTrue(accept.test(new com.replysis.backend.security.AppInfo("mac", "1.0.250")));
        assertTrue(accept.test(new com.replysis.backend.security.AppInfo("mac", "1.0.251")));
        assertTrue(accept.test(new com.replysis.backend.security.AppInfo("mac", "1.1.0")));
        assertTrue(accept.test(new com.replysis.backend.security.AppInfo("mac", "2.0.1")));
        assertFalse(accept.test(new com.replysis.backend.security.AppInfo("mac", "1.0.249")), "older Macs count anything but 400 as a bad line");
        assertFalse(accept.test(new com.replysis.backend.security.AppInfo("mac", "1.0.9")));
        assertFalse(accept.test(new com.replysis.backend.security.AppInfo("mac", "")), "Macs from before the version label");
        assertFalse(accept.test(new com.replysis.backend.security.AppInfo("windows", "1.0.31")), "Windows only accepts 400 or 200");
        assertFalse(accept.test(new com.replysis.backend.security.AppInfo("windows", "9.9.999")));
        assertFalse(accept.test(null), "no label means an older app");
    }
}
