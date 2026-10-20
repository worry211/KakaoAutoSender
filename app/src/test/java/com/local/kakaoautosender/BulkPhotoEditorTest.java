package com.local.kakaoautosender;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BulkPhotoEditorTest {
  private Context c;
  private Intent intent;

  @Before
  public void prepare() {
    c = RuntimeEnvironment.getApplication();
    Prefs.p(c).edit().clear().commit();
    c.getSharedPreferences("room_media_v1", 0).edit().clear().commit();
    for (String name : new String[] {"a", "b"}) {
      MultiRoomStore.upsert(c, new MultiRoomStore.Profile(name));
      RoomMediaStore.set(c, name, "content://photo/" + name, "image/png", name + ".png");
    }
    intent =
        new Intent(c, BulkRoomEditActivity.class)
            .putStringArrayListExtra(
                BulkRoomEditActivity.EXTRA_ROOMS, new ArrayList<>(Arrays.asList("a", "b")));
  }

  @Test
  public void openingOrCancellingBulkEditorDoesNotOverwriteAnyPhoto() {
    var controller = Robolectric.buildActivity(BulkRoomEditActivity.class, intent).create();
    try {
      assertEquals(0, (int) ReflectionHelpers.getField(controller.get(), "photoAction"));
      assertTrue(contains(controller.get().findViewById(android.R.id.content), "같은 사진 선택"));
      assertEquals("content://photo/a", RoomMediaStore.get(c, "a").uri);
      assertEquals("content://photo/b", RoomMediaStore.get(c, "b").uri);
    } finally {
      controller.destroy();
    }
    assertEquals("content://photo/b", RoomMediaStore.get(c, "b").uri);
  }

  @Test
  public void recreatedEditorPreservesExplicitPhotoRemovalAndOtherDraftFields() {
    Bundle draft = new Bundle();
    draft.putInt("photoAction", 2);
    draft.putString("message", "new common message");
    draft.putBoolean("applyMessage", true);
    draft.putBoolean("applySchedule", true);
    draft.putString("interval", "15");
    var controller = Robolectric.buildActivity(BulkRoomEditActivity.class, intent).create(draft);
    try {
      assertEquals(2, (int) ReflectionHelpers.getField(controller.get(), "photoAction"));
      Bundle restored = new Bundle();
      controller.get().onSaveInstanceState(restored);
      assertEquals(2, restored.getInt("photoAction"));
      assertEquals("new common message", restored.getString("message"));
      assertEquals("15", restored.getString("interval"));
      assertTrue(restored.getBoolean("applyMessage"));
      assertTrue(restored.getBoolean("applySchedule"));
      assertEquals("content://photo/a", RoomMediaStore.get(c, "a").uri);
    } finally {
      controller.destroy();
    }
  }

  private boolean contains(View view, String text) {
    if (view instanceof TextView && ((TextView) view).getText().toString().contains(text))
      return true;
    if (view instanceof android.view.ViewGroup)
      for (int i = 0; i < ((android.view.ViewGroup) view).getChildCount(); i++)
        if (contains(((android.view.ViewGroup) view).getChildAt(i), text)) return true;
    return false;
  }
}
