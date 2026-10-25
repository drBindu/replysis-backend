package com.replysis.backend;

import com.replysis.backend.service.CompanionHub;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** The phone's copy of a laptop answer: what the laptop gets is passed on unchanged, and only for a signed-in desktop app. */
class CompanionHubTests {

    private static final String STREAM =
            "data: {\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"there.\"}}]}\n\n" +
            "data: [DONE]\n\n";

    @Test
    void theLaptopGetsExactlyWhatItGotBefore() throws Exception {
        CompanionHub hub = new CompanionHub();
        ByteArrayOutputStream laptop = new ByteArrayOutputStream();
        CompanionHub.Relay relay = hub.relay("uid1", "windows", "Why us?", "voice", laptop);
        relay.write(STREAM.getBytes(StandardCharsets.UTF_8));
        relay.finish();
        assertEquals(STREAM, laptop.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aStopFromThePhoneReachesTheLaptopOnce() {
        CompanionHub hub = new CompanionHub();
        assertFalse(hub.command("uid1", "stop"), "no laptop has reported yet, so there is nobody to stop");
        assertEquals(0, hub.laptop("uid1", true, "Tell me about").get("phones"));
        assertFalse(hub.command("uid1", "delete everything"), "only stop is accepted");
        assertTrue(hub.command("uid1", "stop"));
        assertEquals("stop", hub.laptop("uid1", true, "Tell me about yourself").get("command"));
        assertNull(hub.laptop("uid1", false, "").get("command"), "handed over once, not again");
    }

    @Test
    void phoneAndGuestAnswersAreNotRelayed() throws Exception {
        CompanionHub hub = new CompanionHub();
        for (String[] who : new String[][] { { "uid1", "android" }, { "uid1", "ios" }, { null, "windows" } }) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CompanionHub.Relay relay = hub.relay(who[0], who[1], "Q", "voice", out);
            relay.write(STREAM.getBytes(StandardCharsets.UTF_8));
            relay.finish();   // a relay that copies nothing must still be safe to finish
            assertEquals(STREAM, out.toString(StandardCharsets.UTF_8));
        }
    }
}
