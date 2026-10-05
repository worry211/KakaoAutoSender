namespace VoiceRoomManager.Windows.Core;
// Presentation only: never used to decide which control to click or to prove activity.
internal static class DiagnosticPresentation
{
    public static string Summary(string detail)
    {
        if (string.IsNullOrWhiteSpace(detail)) return "";
        if (detail.Contains("Windows 한국어 OCR이 없습니다", StringComparison.Ordinal)) return "Windows 한국어 OCR을 설치한 뒤 ‘지금 확인’을 눌러 주세요.";
        if (detail.Contains("Windows OCR 실행 실패", StringComparison.Ordinal)) return "Windows 화면 인식 기능을 실행하지 못했습니다. 고급 진단을 확인해 주세요.";
        if (detail.Contains("미리보기 제목이 등록한 이름", StringComparison.Ordinal)) return "미리보기와 등록한 방 이름이 일치하지 않습니다. 정확한 이름을 확인해 주세요.";
        if (detail.Contains("미리보기 참여자/개설일", StringComparison.Ordinal)) return "미리보기의 방 정보를 읽지 못했습니다. Kakao 화면을 확인해 주세요.";
        if (detail.Contains("입장 버튼을 찾지 못함", StringComparison.Ordinal)) return "Kakao 입장 화면을 기다리고 있습니다. 연결과 브라우저의 Kakao 열기 안내를 확인해 주세요.";
        if (detail.Contains("미리보기 방 이름 OCR 확인 실패", StringComparison.Ordinal)) return "미리보기의 방 이름을 확인하지 못했습니다. 현재 버전과 정확한 방 이름을 확인해 주세요.";
        var final = detail.Split(" → ",StringSplitOptions.None).Last().Replace("preview=","");
        var cut = final.IndexOf(" · ",StringComparison.Ordinal);
        if (cut > 0) final = final[..cut];
        return final.Length > 150 ? final[..150] + "…" : final;
    }
}
