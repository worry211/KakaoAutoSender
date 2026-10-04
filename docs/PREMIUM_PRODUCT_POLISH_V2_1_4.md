# Premium product polish v2.1.4

This release is a seller-ready presentation and usability pass layered on top of the existing v2.1.3 launch hotfix and v2.1.2 security hardening.

## Dashboard

- Rebuilt the status hero with clearer automation state, compact entitlement/permission health and protected-routing presentation.
- Reduced developer/diagnostic-looking copy on the normal customer surface.
- Rebalanced start/stop hierarchy so the primary action is visually dominant and destructive actions are quieter.
- Reworked room cards with connection badges, schedule hierarchy, message preview and cleaner action buttons.
- Added a dedicated operation-summary card and a clearer first-use empty state.
- Reorganized settings/support tools and surfaced the fail-closed anti-misdelivery guarantee as customer-facing trust copy.

## Room discovery

- Replaced the stock Android list picker with a branded custom picker.
- Added live room-name search, result count, existing-room awareness and safer row hierarchy.
- Kept direct/manual connection as an explicit fallback instead of mixing it into the normal path.
- Preserved exact-room fail-closed behavior and same-name ambiguity protections.

## Room editor

- Rebuilt visual hierarchy across connection, message, image, schedule, limits and actions.
- Added clearer explanations for image fail-closed behavior, schedule staggering and per-room isolation.
- Rebalanced save/test/delete actions to reduce accidental destructive taps.
- Standardized customer-facing Korean copy and status wording.

## Non-goals

- No changes to licensing authority, Discord administrator scope, server entitlement rules, Kakao routing safety, scheduler semantics or signing identity.
- Physical-device KakaoTalk QA is still required before raising the production minimum version to 25.
