package com.local.kakaoautosender;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.widget.*;

/** Phone-only onboarding; does not hide developer-option or pairing requirements. */
public final class PanelOffActivity extends Activity {
  private final Handler main = new Handler(Looper.getMainLooper());
  private TextView status;
  private final PhotoSetupActionGate actions = new PhotoSetupActionGate();
  private final java.util.ArrayList<Button> setupButtons = new java.util.ArrayList<>();
  private boolean resumed;
  private final Runnable refresh =
      new Runnable() {
        @Override
        public void run() {
          if (!resumed) return;
          status.setText(
              actions.pending() ? "서버 확인 중 · 아래 중지 버튼으로 취소할 수 있습니다." : PanelOffManager.status());
          boolean available =
              !actions.pending()
                  && !PanelOffManager.busy()
                  && !PanelOffManager.engaged()
                  && !PhotoTourController.active();
          for (Button b : setupButtons) b.setEnabled(available);
          main.postDelayed(this, 1000L);
        }
      };

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    PremiumChrome.applyWindow(this);
    getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    ScrollView scroll = new ScrollView(this);
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    int pad = Math.round(20 * getResources().getDisplayMetrics().density);
    body.setPadding(pad, pad, pad, pad);
    body.setBackgroundColor(Color.rgb(10, 14, 22));
    scroll.addView(body);
    PhotoSettingsLayout.apply(this, scroll);
    text(body, "화면 끄고 사진 순회 · 시험판", 22);
    status = text(body, "", 14);
    text(
        body,
        "Android 11 이상 · PC·USB·별도 보조 앱 없이 이 휴대폰에 연결합니다. Wi-Fi와 개발자 옵션의 무선 디버깅이 필요합니다. 재부팅·Wi-Fi 변경"
            + " 후 다시 연결해야 할 수 있습니다.",
        14);
    text(
        body,
        "화면 패널 전원만 끄고 기기를 잠금 해제 상태로 유지합니다. 전원 버튼으로 잠근 상태에서 보내는 기능이 아닙니다. 화면을 끄기 전에 휴대폰 사용을 마치세요. 전원"
            + " 버튼·잠금 또는 연결 끊김은 전송을 중지합니다.",
        14);
    button(
        body,
        "1. 개발자 옵션 열기",
        () -> {
          try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
          } catch (Exception e) {
            Toast.makeText(
                    this,
                    "설정 → 휴대전화 정보 → 소프트웨어 정보에서 빌드 번호를 7번 누른 뒤 개발자 옵션을 여세요.",
                    Toast.LENGTH_LONG)
                .show();
          }
        });
    text(
        body,
        "개발자 옵션이 없다면: 설정 → 휴대전화 정보 → 소프트웨어 정보 → 빌드 번호를 7번 누르세요. 메뉴 이름은 기기마다 다릅니다. 코드 입력에는 기존 숫자"
            + " 키보드를 사용하고, 사진 전송 전에는 사진 키보드로 바꾸세요.",
        14);
    text(
        body,
        "처음 한 번: 무선 디버깅을 켜고 ‘페어링 코드로 기기 페어링’을 엽니다. 분할 화면으로 이 앱과 설정을 함께 열어 포트와 코드를 입력하세요. 화면에"
            + " 192.168.x.x:12345로 표시되면 끝의 12345가 포트입니다.",
        14);
    EditText pairPort = field(body, "페어링 창의 포트");
    EditText code = field(body, "페어링 창의 6자리 코드");
    code.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    setupButtons.add(
        button(
            body,
            "2. 최초 페어링",
            () -> {
              String value = code.getText().toString();
              String requestedPort = pairPort.getText().toString();
              code.setText("");
              authorize(() -> PanelOffManager.connect(this, requestedPort, value, true));
            }));
    text(body, "페어링 완료 후: 무선 디버깅 기본 화면으로 돌아가 ‘IP 주소 및 포트’의 연결 포트를 입력합니다. 위 페어링 포트와 다릅니다.", 14);
    EditText port = field(body, "무선 디버깅 기본 화면의 연결 포트");
    setupButtons.add(
        button(
            body,
            "3. 휴대폰 자체 연결",
            () -> {
              String requestedPort = port.getText().toString();
              authorize(() -> PanelOffManager.connect(this, requestedPort, null, false));
            }));
    setupButtons.add(
        button(
            body,
            "사진 없이 5초 화면 끄기 시험",
            () ->
                authorize(
                    () -> {
                      String error = PanelOffManager.trial(this);
                      if (!error.isEmpty()) Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                    })));
    text(
        body,
        "시험은 사진을 보내지 않습니다. 화면이 실제로 꺼졌다가 돌아오는지 확인하세요. 제어 API 응답만으로 실제 패널 상태나 사진 수신이 확인되지는 않습니다. 지원하지"
            + " 않는 기기에서는 시작하지 않습니다.",
        13);
    button(
        body,
        "화면 즉시 복구·사진 순회 중지",
        () -> {
          actions.cancel();
          PhotoTourController.stop(this, "사용자가 화면 복구와 순회 중지를 요청했습니다.");
          PanelOffManager.end("사용자 화면 복구 요청됨");
        });
    button(
        body,
        "무선 연결 해제",
        () -> {
          actions.cancel();
          PanelOffManager.disconnect(this);
        });
    button(
        body,
        "사진 여러 방 전송으로",
        () -> {
          startActivity(new Intent(this, PhotoTourActivity.class));
          finish();
        });
    button(
        body,
        "오픈소스 고지",
        () -> {
          try (java.io.InputStream in = getAssets().open("wireless-adb-notices.txt")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            new android.app.AlertDialog.Builder(this)
                .setTitle("오픈소스 고지")
                .setMessage(out.toString("UTF-8"))
                .setPositiveButton("닫기", null)
                .show();
          } catch (Exception ignored) {
          }
        });
    button(body, "돌아가기", this::finish);
    setContentView(scroll);
  }

  private void authorize(Runnable action) {
    LicenseManager.runAuthorized(
        this,
        actions.guard(
            () -> {
              if (resumed && !isFinishing()) action.run();
            }));
  }

  @Override
  public void onResume() {
    super.onResume();
    if (!AppIntegrity.isAuthentic(this)) {
      AppIntegrity.trip(this);
      finish();
      return;
    }
    if (!LicenseManager.isUsable(this)) {
      LicenseManager.route(this);
      return;
    }
    resumed = true;
    refresh.run();
  }

  @Override
  public void onPause() {
    actions.cancel();
    resumed = false;
    main.removeCallbacks(refresh);
    super.onPause();
  }

  private EditText field(LinearLayout p, String hint) {
    EditText v = new EditText(this);
    v.setHint(hint);
    v.setTextColor(Color.WHITE);
    v.setHintTextColor(Color.LTGRAY);
    v.setSingleLine(true);
    v.setSaveEnabled(false);
    v.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    v.setInputType(InputType.TYPE_CLASS_NUMBER);
    p.addView(v);
    return v;
  }

  private TextView text(LinearLayout p, String label, int size) {
    TextView v = new TextView(this);
    v.setText(label);
    v.setTextColor(Color.WHITE);
    v.setTextSize(size);
    v.setPadding(0, 14, 0, 18);
    p.addView(v);
    return v;
  }

  private Button button(LinearLayout p, String label, Runnable click) {
    Button b = new Button(this);
    b.setText(label);
    b.setAllCaps(false);
    PhotoControls.style(b, label);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.bottomMargin = 14;
    p.addView(b, params);
    b.setOnClickListener(v -> click.run());
    return b;
  }
}
