# Plan: Fix CDN auto-switch freeze

## Problem
A video played with **Auto** CDN mode freezes on a stuck frame. Manually switching to a specific CDN plays normally.

## Root causes found
1. **Cross-video cache signature mismatch** in `CdnSelector`:
   - It caches only the winning CDN host keyed by a "region" derived from the current video's base URL.
   - On cache hit it applies the cached host to the new video's signed `baseUrl` via `replaceHost(...)`.
   - Bilibili media URLs are signed per host, so reusing another video's best host produces an invalid/stalling URL.
2. **No CDN failover in the DASH manifest**:
   - `PlaybackTrack.toRepresentation` emits only one `<BaseURL>`.
   - ExoPlayer cannot fall back to a backup CDN mid-playback when the auto-selected CDN degrades.

## Changes

### 1. `app/src/main/java/com/kirin/mt/core/player/CdnSelector.kt`
- Replace the host-only region cache with a cache keyed by the **full original base URL** (or a stable hash), storing the **full best URL**.
- Remove `replaceHost(...)`; on cache hit return the cached full URL directly.
- Introduce a `CdnSelection` result type:
  ```kotlin
  data class CdnSelection(
    val primaryUrl: String,
    val fallbackUrls: List<String>,
  )
  ```
- For `PlaybackCdnPreference.Auto`:
  - Run `CdnSpeedTester.measure(...)` when not cached.
  - `primaryUrl` = highest-scoring successful measurement.
  - `fallbackUrls` = remaining successful measurements sorted by score.
  - If all measurements fail, return the original `baseUrl` as primary with original backups as fallbacks.
- For manual preferences:
  - `primaryUrl` = `CdnRewriter.rewrite(baseUrl, preference)`.
  - `fallbackUrls` = `backupUrls` rewritten with the same preference, filtered by `isEligibleCandidate`.
- Keep existing bad-host filtering (`mcdn`, `szbdyd`, bare IPs).

### 2. `app/src/main/java/com/kirin/mt/ui/player/PlayerScreen.kt`
- When applying CDN selection, update both fields:
  ```kotlin
  track.copy(
    baseUrl = selection.primaryUrl,
    backupUrls = selection.fallbackUrls,
  )
  ```
- In `PlaybackTrack.toRepresentation`, emit all URLs as redundant `<BaseURL>` elements (primary first, then fallbacks). ExoPlayer uses the first available URL and fails over to the next on HTTP errors.

## Verification
- Manual test: play a video in Auto mode, then play a different video and confirm no freeze.
- Verify manual CDN preferences still work.
- (Optional) Log generated manifest to confirm multiple `<BaseURL>` elements.

## Out of scope
- Adding a dedicated buffering/stall detector with mid-playback source reload. The cache fix + manifest fallbacks should resolve the reported issue. We can add stall recovery later if needed.
