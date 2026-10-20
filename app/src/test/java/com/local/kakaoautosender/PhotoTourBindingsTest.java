package com.local.kakaoautosender;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.pm.PackageInfo;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35})
public class PhotoTourBindingsTest {
  private Context c;

  @Before
  public void setup() {
    c = RuntimeEnvironment.getApplication();
    Prefs.p(c).edit().clear().commit();
    version(7);
  }

  private void version(int n) {
    PackageInfo p = new PackageInfo();
    p.packageName = KakaoNotificationListener.KAKAO_PACKAGE;
    p.versionCode = n;
    Shadows.shadowOf(c.getPackageManager()).installPackage(p);
  }

  private PhotoTourBindings.Binding binding(String room, String header) {
    return new PhotoTourBindings.Binding(
        room,
        new PhotoUiWorkflow.Anchor(7, "editor", header),
        new ArrayList<>(Collections.singletonList("kakao-shortcut:" + room)),
        7,
        new PhotoUiWorkflow.Button(8, "photo.Activity", "layout", "send", "Button", "전송"));
  }

  @Test
  public void bindingRoundTripRequiresCurrentKakaoVersion() {
    assertNull(PhotoTourBindings.firstTemplate(c));
    assertEquals("", PhotoTourBindings.save(c, binding("a", "header-a")));
    PhotoTourBindings.Binding b = PhotoTourBindings.get(c, "a");
    assertNotNull(b);
    assertNotNull(PhotoTourBindings.firstTemplate(c));
    assertTrue(b.matches(new PhotoUiWorkflow.Anchor(90, "editor", "header-a")));
    assertFalse(b.matches(new PhotoUiWorkflow.Anchor(90, "editor", "other")));
    version(8);
    assertNull(PhotoTourBindings.get(c, "a"));
    assertNull(PhotoTourBindings.firstTemplate(c));
  }

  @Test
  public void indistinguishableRoomHeadersCannotBeStoredAsSeparateRecipients() {
    assertEquals("", PhotoTourBindings.save(c, binding("a", "same")));
    assertFalse(PhotoTourBindings.save(c, binding("b", "same")).isEmpty());
    assertNull(PhotoTourBindings.get(c, "b"));
    assertEquals("", PhotoTourBindings.save(c, binding("a", "same")));
  }

  @Test
  public void malformedBindingIsRejected() {
    Prefs.p(c).edit().putString("photo_tour_bindings_v1", "{broken").commit();
    assertNull(PhotoTourBindings.get(c, "a"));
  }

  @Test
  public void restoredTemplateCannotClickUntilExactPreviewMatches() {
    PhotoTourBindings.Binding b = binding("a", "a");
    PhotoUiWorkflow w = new PhotoUiWorkflow();
    PhotoUiWorkflow.Anchor a = new PhotoUiWorkflow.Anchor(2, "editor", "a");
    w.bindVerifiedRoute(a, b.button);
    assertTrue(w.begin(a));
    assertFalse(w.claim(new PhotoUiWorkflow.Button(3, "wrong", "layout", "send", "Button", "전송")));
    assertTrue(
        w.claim(new PhotoUiWorkflow.Button(3, "photo.Activity", "layout", "send", "Button", "전송")));
    assertFalse(w.claim(b.button));
  }
}
