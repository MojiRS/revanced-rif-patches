# Patch details

How each patch works, and its settings (under **Settings → ReVanced** in the patched app). For a summary, see the [README](README.md).

All patches target **rif is fun 5.6.22**. rif is R8-obfuscated, and the free (`com.andrewshu.android.reddit`) and golden platinum (`com.andrewshu.android.redditdonation`) builds are obfuscated independently: same code, different class and member names. Patches find their targets by shape (signatures, strings, call patterns) or through the per-build symbol table `RIF_BUILDS` in `patches/src/main/kotlin/app/revanced/patches/rif/shared/Rif.kt`.

## Disable ads

*Free app only; golden platinum has no ads.*

Removes AppLovin native feed ads, banner ads, and image/album-viewer ads, so no ad slots render and no ad-network requests are made:

- Forces the feed's ad-slot gate (the only place native-ad placeholders are inserted) and the `isAdsEnabledAndUnblocked()` checks to `false`.
- No-ops the native-ad, banner and album-viewer ad loaders.
- Keeps the album viewer from wrapping its images in the ad-inserting adapter.

Setting: *Block ads*.

## Inline comment images

Shows images, GIFs and videos linked in comments and text posts inline.

**Images**

- Direct image links (i.redd.it, preview.redd.it, `.jpg` `.png` `.webp` `.gif` …), animated **GIFs and WebP**, **Giphy** (by id), and common hosts resolved through their `og:image` tag (imgur pages, reddit galleries, redgifs, gfycat, Tenor).
- Bare links, and Reddit-app `[gif]` markers, are replaced by the image. `[text](url)` links keep their text, with the image on the line below.
- Multi-image **imgur albums** (listed through imgur's API with rif's client ID) show an "n/x" badge and ◀ ▶ buttons: tap the left or right third of the image to cycle through the album, or the middle to open it in rif's album viewer.
- Images shrink to fit indented replies.
- Long pressing an image selects its comment, like tapping the comment's text.

**Videos**

- Comment videos (`reddit.com/link/…/video/…/player`), v.redd.it links, and imgur `.gifv`/`.mp4` play inline on their own line below the link, muted and looping (or tap-to-play).
- Reddit videos are read from their DASH manifest: the largest rendition up to 480p plays inline, the smallest supplies the still frame, and the separate sound file plays alongside (kept in sync) once unmuted.
- Tap a video for controls like rif's own player's, built from rif's own icons and colors: back to start, play/pause, repeat (on by default), sound, seek, and full screen. Full screen opens rif's player at the same position, repeat and sound settings.
- The video is drawn by a `TextureView` laid over the space the text reserves for it, in the frame rif wraps comment and post bodies in. Players are released while scrolled away or while rif is hidden (e.g. the phone is locked), and restored where they were.

Settings: *Inline images*, *Scale inline images to fit*, *Inline album navigation* (off: no arrows, tapping an album opens it), *Inline videos*, *Autoplay inline videos*, *Long press image to select comment* with a *Long press delay* slider.

How it works: images are fetched and embedded on rif's background thread, when it renders a comment or post body (before it's shown). A hook after each body `setText` then starts GIFs and adds video overlays on the main thread.

## Fix comment video links

Makes videos posted in comments play in rif's video player instead of failing with "error retrieving Reddit video metadata".

rif resolves a `reddit.com/link/{id}/video/{mediaId}/player` link by looking `{id}` up as a post. For a video posted in a comment, `{id}` is the comment, and Reddit's API exposes no media for comments, so the lookup always fails. The streams themselves are public at `v.redd.it/link/{id}/asset/{mediaId}/`; when rif's lookup comes back empty, this patch builds rif's video object pointing at them, and rif plays it like any other Reddit video.

## Fix imgur albums

Fixes imgur albums crashing or failing to load when patched alongside the official ReVanced rif patches.

The official patches move album loading from rif's defunct proxy to imgur's v3 API. This patch fixes the crash (or bounce back a page) caused by an invalid `String.concat` call in that code, and sends rif's imgur client ID with album requests, which the proxy used to add, so they aren't rejected as anonymous. Without the official patches, that code isn't present and this patch changes nothing.

## Fix YouTube videos

Fixes the built-in YouTube player.

Without it, YouTube links opened in rif show a black player and "There was an error initializing the YouTubePlayer (Error code: 0)". rif's YouTube player library tells YouTube the embed comes from `https://www.youtube.com`. YouTube now rejects embeds unless that origin is `https://` plus the app's package name. This patch sends the app's own origin instead, the same fix as newer versions of the library.

## YouTube player controls

Double-tap to seek, hold for 2x speed, and speed and captions controls in the YouTube player.

rif's YouTube player has no controls of its own beyond a play button, seek bar and full screen. This patch adds:

- **Double-tap** the left or right half of the video to skip back or forward 10 seconds, with a rewind or fast-forward icon and the time skipped. Keep tapping to skip more. A single tap still shows or hides the controls.
- **Hold** the video to play at 2x speed until you let go.
- A **settings button** (top right, with the controls) opening a popup like rif's own video player's: playback speed and captions on or off.
- Captions are the same size in portrait and landscape, and sit above the bottom controls when the video leaves room for it (the picture is never resized; no lift in landscape). The settings decide for every video; a choice in the popup lasts for that video only. The screen stays on while a video plays, a tap on a finished video replays it, and the controls keep clear of rounded screen corners and cutouts.

Settings (YouTube category, all off by default): *Captions on by default: landscape*, *vertical* and *Shorts*. A `youtube.com/shorts` link uses the Shorts setting, a video known to be vertical the vertical one, anything else the landscape one.

How it works: rif puts the YouTube player library's control overlay over the embedded player. A hook where the overlay is attached adds the gestures, the feedback icons and the settings button, and drives the embedded player through its JavaScript API. A hook on the player's state callbacks keeps the screen on and swaps the play button for a replay tap at the end. A second hook adds the `cc_lang_pref` captions playerVar to the player options when one of the three captions settings is on; the state hook also applies the setting at the start of each video (YouTube remembers the viewer's last caption choice) and sets the caption size from the player's size. To lift captions it shrinks the iframe element from the player page by the spare room around the video; the video's shape comes from YouTube's "original aspect ratio" thumbnails (unknown means no lift). Video quality can't be chosen: YouTube's embed ignores it.


## Fix share links

Opens Reddit share links (reddit.com/r/…/s/…) directly in rif instead of bouncing through the browser.

Reddit's "Share" button now produces links like `reddit.com/r/sub/s/AbC123`. rif doesn't recognise them as Reddit links, so it opens them in its in-app browser; Reddit redirects that to the post, which rif then opens as a second screen on top of a blank browser, so Back lands on the blank page. This patch looks up where a share link points (a quick lookup, remembered while rif runs) and gives rif the post's address instead, so it opens like any other post and Back returns to where you were. It covers links tapped inside rif and links opened from other apps. `np.` and `old.` links work too. If a link can't be resolved (offline, Reddit refuses), rif behaves as it did before.

No settings.

How it works: a hook at the top of rif's link router resolves `/r|u|user/{sub}/s/{code}` links on a background thread (HEAD request, redirects followed by hand, tracking parameters removed) and calls the router again on the main thread with the result. A second hook at the start of `MainActivity`'s VIEW-intent handling does the same for `/r/` links from other apps, then starts a fresh `MainActivity` on the post and finishes the blank one.
