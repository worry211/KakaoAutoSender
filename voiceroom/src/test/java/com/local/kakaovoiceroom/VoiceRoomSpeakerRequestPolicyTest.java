package com.local.kakaovoiceroom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VoiceRoomSpeakerRequestPolicyTest {
    @Test public void recognizesOnlySpeakerRequestContext() {
        assertTrue(VoiceRoomSpeakerRequestPolicy.isRequestContext("스피커 요청이 도착했습니다"));
        assertTrue(VoiceRoomSpeakerRequestPolicy.isRequestContext("스피커로 참여 요청"));
        assertTrue(VoiceRoomSpeakerRequestPolicy.isRequestContext("발언 요청"));
        assertFalse(VoiceRoomSpeakerRequestPolicy.isRequestContext("친구 요청"));
        assertFalse(VoiceRoomSpeakerRequestPolicy.isRequestContext("요청"));
    }

    @Test public void rejectActionsAreStrictAndNeverTreatAcceptAsReject() {
        assertTrue(VoiceRoomSpeakerRequestPolicy.isRejectAction("거절"));
        assertTrue(VoiceRoomSpeakerRequestPolicy.isRejectAction("스피커 요청 거부"));
        assertTrue(VoiceRoomSpeakerRequestPolicy.isRejectAction("스피커로 참여 요청 거절"));
        assertFalse(VoiceRoomSpeakerRequestPolicy.isRejectAction("취소"));
        assertFalse(VoiceRoomSpeakerRequestPolicy.isRejectAction("수락"));
        assertTrue(VoiceRoomSpeakerRequestPolicy.isAcceptAction("스피커 요청 수락"));
        assertTrue(VoiceRoomSpeakerRequestPolicy.isAcceptAction("승인"));
        assertTrue(VoiceRoomSpeakerRequestPolicy.isAcceptAction("스피커로 참여"));
        assertFalse(VoiceRoomSpeakerRequestPolicy.isAcceptAction("스피커로 참여 요청 거절"));
    }

    @Test public void interpretsOnlyActionOrientedRequestToggleLabels() {
        assertEquals(VoiceRoomSpeakerRequestPolicy.ToggleState.ACCEPTING,
                VoiceRoomSpeakerRequestPolicy.requestToggleState("스피커 요청 끄기"));
        assertEquals(VoiceRoomSpeakerRequestPolicy.ToggleState.ACCEPTING,
                VoiceRoomSpeakerRequestPolicy.requestToggleState("스피커 신청 받지 않기"));
        assertEquals(VoiceRoomSpeakerRequestPolicy.ToggleState.ACCEPTING,
                VoiceRoomSpeakerRequestPolicy.requestToggleState("스피커 요청 차단하기"));
        assertEquals(VoiceRoomSpeakerRequestPolicy.ToggleState.BLOCKED,
                VoiceRoomSpeakerRequestPolicy.requestToggleState("스피커 요청 켜기"));
        assertEquals(VoiceRoomSpeakerRequestPolicy.ToggleState.BLOCKED,
                VoiceRoomSpeakerRequestPolicy.requestToggleState("스피커 요청 받기"));
        assertEquals(VoiceRoomSpeakerRequestPolicy.ToggleState.BLOCKED,
                VoiceRoomSpeakerRequestPolicy.requestToggleState("스피커 요청 허용하기"));

        // State/prose labels alone never authorize a toggle click.
        assertEquals(VoiceRoomSpeakerRequestPolicy.ToggleState.UNKNOWN,
                VoiceRoomSpeakerRequestPolicy.requestToggleState("스피커 요청"));
        assertEquals(VoiceRoomSpeakerRequestPolicy.ToggleState.UNKNOWN,
                VoiceRoomSpeakerRequestPolicy.requestToggleState("스피커 요청 차단"));
        assertEquals(VoiceRoomSpeakerRequestPolicy.ToggleState.UNKNOWN,
                VoiceRoomSpeakerRequestPolicy.requestToggleState("스피커 요청 허용"));
    }

    @Test public void onlyAcceptingStateRequestsAProtectiveToggle() {
        assertTrue(VoiceRoomSpeakerRequestPolicy.shouldDisableRequests(
                VoiceRoomSpeakerRequestPolicy.ToggleState.ACCEPTING));
        assertFalse(VoiceRoomSpeakerRequestPolicy.shouldDisableRequests(
                VoiceRoomSpeakerRequestPolicy.ToggleState.BLOCKED));
        assertFalse(VoiceRoomSpeakerRequestPolicy.shouldDisableRequests(
                VoiceRoomSpeakerRequestPolicy.ToggleState.UNKNOWN));
    }
}
