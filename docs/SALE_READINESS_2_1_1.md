# Sale readiness 2.1.1

Final buyer-flow hardening before wider sale:

- commercial version bumped to 2.1.1 / versionCode 22
- boot receiver kept internal while still receiving system boot/package broadcasts
- activation key paste accepts a whole seller message and extracts the exact KM key, then validates locally before network activation
- photo selection stores only concrete image MIME types; wildcard/unknown types are rejected before replacing the previous photo
- persisted photo URI permissions are released best-effort when a photo is replaced, removed, or the room is deleted
- text delivery now requires an actual free-form RemoteInput; data-only reply inputs fail closed
- mixed text + photo delivery also fails closed if the text portion cannot be represented
- regression tests cover activation-key normalization, MIME resolution, and free-form RemoteInput behavior

Physical-device acceptance remains required for KakaoTalk-version/device-specific notification reply behavior.
