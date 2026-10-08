import { execFileSync } from "node:child_process";
import { writeFileSync, readFileSync } from "node:fs";

// Current-user DPAPI; plaintext only travels through private child-process pipes.
// It never enters command arguments, a temporary file, logs or the clipboard.
export function protect(path, value) {
  const bytes = execFileSync(
    "powershell.exe",
    [
      "-NoProfile",
      "-NonInteractive",
      "-Command",
      "Add-Type -AssemblyName System.Security; [Console]::InputEncoding=New-Object System.Text.UTF8Encoding($false); $inputBytes=[System.Text.Encoding]::UTF8.GetBytes([Console]::In.ReadToEnd()); $protectedBytes=[System.Security.Cryptography.ProtectedData]::Protect($inputBytes,$null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser); [Console]::Out.Write([Convert]::ToBase64String($protectedBytes))",
    ],
    {
      input: JSON.stringify(value),
      encoding: "utf8",
      stdio: ["pipe", "pipe", "pipe"],
    },
  );
  writeFileSync(path, Buffer.from(bytes.trim(), "base64"), {
    flag: "wx",
    mode: 0o600,
  });
}
export function unprotect(path) {
  const value = execFileSync(
    "powershell.exe",
    [
      "-NoProfile",
      "-NonInteractive",
      "-Command",
      "Add-Type -AssemblyName System.Security; [Console]::OutputEncoding=New-Object System.Text.UTF8Encoding($false); $protectedBytes=[Convert]::FromBase64String([Console]::In.ReadToEnd()); $plainBytes=[System.Security.Cryptography.ProtectedData]::Unprotect($protectedBytes,$null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser); [Console]::Out.Write([System.Text.Encoding]::UTF8.GetString($plainBytes))",
    ],
    {
      input: readFileSync(path).toString("base64"),
      encoding: "utf8",
      stdio: ["pipe", "pipe", "pipe"],
    },
  );
  return JSON.parse(value);
}
