package com.local.kakaoautosender;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** Explicit user handoff: Kakao selects the recipient and confirms sending in its own UI. */
final class PhotoShareRequest {
  private PhotoShareRequest() {}

  static Intent create(Context context, RoomMediaStore.Media media) throws IOException {
    if (media == null
        || !media.hasImage()
        || !media.mime.toLowerCase(Locale.ROOT).startsWith("image/")
        || media.mime.contains("*")) throw new IOException("사진을 다시 선택하세요.");
    Uri uri = Uri.parse(media.uri);
    if (!"content".equals(uri.getScheme())) throw new IOException("사진을 다시 선택하세요.");
    try (InputStream input = context.getContentResolver().openInputStream(uri)) {
      if (input == null) throw new IOException("선택한 사진을 찾을 수 없습니다.");
      input.read();
    } catch (SecurityException error) {
      throw new IOException("사진 읽기 권한이 만료되었습니다. 사진을 다시 선택하세요.", error);
    } catch (RuntimeException error) {
      throw new IOException("선택한 사진을 읽을 수 없습니다. 사진을 다시 선택하세요.", error);
    }
    Intent request = new Intent(Intent.ACTION_SEND);
    request.setPackage(KakaoNotificationListener.KAKAO_PACKAGE);
    request.setType(media.mime);
    request.putExtra(Intent.EXTRA_STREAM, uri);
    request.setClipData(ClipData.newUri(context.getContentResolver(), "사진", uri));
    request.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    return request;
  }
}
