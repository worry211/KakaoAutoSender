using System.Windows.Automation;

namespace VoiceRoomManager.Windows.Core;

internal static class BrowserUrlEvidence
{
    public static bool Matches(IntPtr host, string expected)
    {
        try
        {
            var root = AutomationElement.FromHandle(host);
            var edits = root.FindAll(TreeScope.Descendants, new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Edit));
            foreach (AutomationElement edit in edits)
            {
                if (edit.Current.IsOffscreen || !edit.TryGetCurrentPattern(ValuePattern.Pattern, out var p)) continue;
                var value = ((ValuePattern)p).Current.Value.Trim();
                if (!value.Contains("://", StringComparison.Ordinal)) value = "https://" + value;
                if (OpenChatLinkRegistry.IsSupported(value) && OpenChatLinkRegistry.Normalize(value) == OpenChatLinkRegistry.Normalize(expected)) return true;
            }
        }
        catch { }
        return false;
    }
}
