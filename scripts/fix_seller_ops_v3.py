from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"expected snippet not found in {path}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# Revoke/delete status is already authoritative on every entitlement call. Keep
# exact REVOKED/DELETED responses instead of masking them as DEVICE_MISMATCH.
replace_once(
    "backend/src/admin.ts",
    '  if (["reset-device", "revoke", "delete"].includes(action))\n    stmts.push(',
    '  if (action === "reset-device")\n    stmts.push(',
)

# min-version is an emergency safety control and must remain usable even when
# release metadata is stale. latest-version still may not be set below min.
replace_once(
    "backend/src/admin.ts",
    '      if (action === "min-version" && version > Number(c.latest_version))\n        throw new ApiError("MIN_VERSION_ABOVE_LATEST", 409);\n',
    '',
)

platform = Path("backend/test/platform.test.ts")
text = platform.read_text(encoding="utf-8")
text = text.replace(
'''  it("keeps version metadata coherent", async () => {
    await env.DB.prepare(
      "UPDATE config SET min_version=20,latest_version=34 WHERE id=1",
    ).run();
    await expect(
      admin(env, seller, "system", "min-version", { version: 35 }, requestId()),
    ).rejects.toMatchObject({ state: "MIN_VERSION_ABOVE_LATEST" });
    await admin(
      env,
      seller,
      "system",
      "min-version",
      { version: 34 },
      requestId(),
    );
    await expect(
      admin(
        env,
        seller,
        "system",
        "latest-version",
        { version: 33, url: "https://example.com/app.apk" },
        requestId(),
      ),
    ).rejects.toMatchObject({ state: "LATEST_VERSION_BELOW_MIN" });
    const updated = await admin(
      env,
      seller,
      "system",
      "latest-version",
      {
        version: 34,
        url: "https://example.com/app.apk",
        notes: "Android v2.3.3 판매판",
      },
      requestId(),
    );
    expect(updated.release_notes).toBe("Android v2.3.3 판매판");
  });''',
'''  it("keeps version metadata coherent without blocking emergency minimums", async () => {
    await env.DB.prepare(
      "UPDATE config SET min_version=20,latest_version=34 WHERE id=1",
    ).run();
    const emergency = await admin(
      env,
      seller,
      "system",
      "min-version",
      { version: 35 },
      requestId(),
    );
    expect(emergency.min_version).toBe(35);
    await expect(
      admin(
        env,
        seller,
        "system",
        "latest-version",
        { version: 34, url: "https://example.com/app.apk" },
        requestId(),
      ),
    ).rejects.toMatchObject({ state: "LATEST_VERSION_BELOW_MIN" });
    const updated = await admin(
      env,
      seller,
      "system",
      "latest-version",
      {
        version: 35,
        url: "https://example.com/app.apk",
        notes: "Android v2.3.3 판매판",
      },
      requestId(),
    );
    expect(updated.release_notes).toBe("Android v2.3.3 판매판");
  });''',
)
text = text.replace(
'''  it("revokes live sessions immediately on irreversible license blocks", async () => {
    const a = await active();
    await mutate("revoke", a.l.license_id, { reason: "환불" });
    const row = await env.DB.prepare(
      "SELECT revoked FROM sessions WHERE license_id=? ORDER BY updated_at DESC LIMIT 1",
    )
      .bind(a.l.license_id)
      .first<any>();
    expect(row?.revoked).toBe(1);
  });''',
'''  it("preserves exact irreversible license states for support diagnostics", async () => {
    const a = await active();
    await mutate("revoke", a.l.license_id, { reason: "환불" });
    expect((await heartbeat(a)).state).toBe("REVOKED");
  });''',
)
text = text.replace(
'''    await mutate("suspend", a.l.license_id, { reason: "지원 확인" });
    const result = await admin(''',
'''    await mutate("suspend", a.l.license_id, { reason: "지원 확인" });
    await env.DB.prepare("UPDATE licenses SET expires_at=? WHERE license_id=?")
      .bind(now() + 60, a.l.license_id)
      .run();
    const result = await admin(''',
)
platform.write_text(text, encoding="utf-8")

seller = Path("backend/test/seller-ops-v3.test.ts")
text = seller.read_text(encoding="utf-8")
text = text.replace(
'''  it("explains coherent-version guardrails", () => {
    expect(friendlyError("MIN_VERSION_ABOVE_LATEST")).toContain("최신 버전");
    expect(friendlyError("LATEST_VERSION_BELOW_MIN")).toContain("최소 지원 버전");
  });''',
'''  it("explains the latest-version floor guardrail", () => {
    expect(friendlyError("LATEST_VERSION_BELOW_MIN")).toContain("최소 지원 버전");
  });''',
)
seller.write_text(text, encoding="utf-8")

print("seller operations v3 semantic fixes applied")
