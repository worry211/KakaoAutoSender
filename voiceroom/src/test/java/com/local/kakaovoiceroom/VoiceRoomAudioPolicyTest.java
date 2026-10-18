package com.local.kakaovoiceroom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VoiceRoomAudioPolicyTest {
    @Test public void speakerActionLabelsDescribeCurrentState() {
        assertEquals(VoiceRoomAudioPolicy.State.ON,
                VoiceRoomAudioPolicy.speakerState("스피커 끄기"));
        assertEquals(VoiceRoomAudioPolicy.State.OFF,
                VoiceRoomAudioPolicy.speakerState("스피커 켜기"));
        assertEquals(VoiceRoomAudioPolicy.State.UNKNOWN,
                VoiceRoomAudioPolicy.speakerState("스피커"));
    }

    @Test public void microphoneActionLabelsDescribeCurrentState() {
        assertEquals(VoiceRoomAudioPolicy.State.ON,
                VoiceRoomAudioPolicy.micState("마이크 음소거"));
        assertEquals(VoiceRoomAudioPolicy.State.OFF,
                VoiceRoomAudioPolicy.micState("마이크 음소거 해제"));
        assertEquals(VoiceRoomAudioPolicy.State.UNKNOWN,
                VoiceRoomAudioPolicy.micState("마이크"));
    }

    @Test public void onlyKnownOnStateIsAutoToggled() {
        assertTrue(VoiceRoomAudioPolicy.shouldToggleToProtect(VoiceRoomAudioPolicy.State.ON));
        assertFalse(VoiceRoomAudioPolicy.shouldToggleToProtect(VoiceRoomAudioPolicy.State.OFF));
        assertFalse(VoiceRoomAudioPolicy.shouldToggleToProtect(VoiceRoomAudioPolicy.State.UNKNOWN));
    }
}