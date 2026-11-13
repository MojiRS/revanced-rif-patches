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

- Direct image links (i.redd.it, preview.redd.it, `.jpg` `.png` `.webp` `.gif` …), animated **GIFs and WebP**, **Giphy** (by id), and common hosts resolved through their `og:image` tag (imgur pages, reddit galleries, redgifs, gfycat, Tenor, imgflip).
- Bare links, and Reddit-app `[gif]` markers, are replaced by the image. `[text](url)` links keep their text, with the image on the line below.
- Multi-image **imgur albums** (listed through imgur's API with rif's client ID) show an "n/x" badge and ◀ ▶ buttons: tap the left or right third of the image to cycle through the album, or the middle to open it in rif's album viewer.
- Images shrink to fit indented replies.
- Long pressing an image selects its comment, like tapping the comment's text.

**Videos**

- Comment videos (`reddit.com/link/…/video/…/player`), v.redd.it links, and imgur `.gifv`/`.mp4` play inline on their own line below the link, muted and looping (or tap-to-play).
- Reddit videos are read from their DASH manifest: the largest rendition up to 480p plays inline, the smallest supplies the still frame, and the separate sound file plays alongside (kept in sync) once unmuted.
- Tap a video for controls like rif's own player's, built from rif's own icons and colors: back to start, play/pause, repeat (on by default), sound, seek, and full screen. Full screen opens rif's player at the same position, repeat and sound settings.
- The video is drawn by a `TextureView` laid over the space the text reserves for it, in the frame rif wraps comment and post bodies in. Players are released while scrolled away or while rif is hidden (e.g. the phone is locked), and restored where they were.

Settings: *Inline images*, *Scale inline images to fit*, *Limit inline media width* and *Limit inline media height* (off by default), each with a *Max width (% of screen)* / *Max height (% of screen)* slider, *Inline album navigation* (off: no arrows, tapping an album opens it), *Inline videos*, *Autoplay inline videos*, *Long press image to select comment* with a *Long press delay* slider.

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

Opens Reddit share links (reddit.com/r/…/s/…) directly in rif instead of bouncing through the browser, and fixes reddit.com/media image links.

`reddit.com/media?url=…` links wrap an image. rif mistakes them for a link to the post with the id `media`, an unrelated r/funny post, so the image never opens. This patch opens the wrapped link instead.

`matrix.redditspace.com/_matrix/media/…` image links have no file extension, so rif opens them in its browser, where Reddit's redirects can end at the same r/funny post. This patch looks up the image behind the link and opens that. With Inline comment images on, these links show inline too.

Reddit's "Share" button now produces links like `reddit.com/r/sub/s/AbC123`. rif doesn't recognise them as Reddit links, so it opens them in its in-app browser; Reddit redirects that to the post, which rif then opens as a second screen on top of a blank browser, so Back lands on the blank page. This patch looks up where a share link points (a quick lookup, remembered while rif runs) and gives rif the post's address instead, so it opens like any other post and Back returns to where you were. It covers links tapped inside rif and links opened from other apps. `np.` and `old.` links work too. If a link can't be resolved (offline, Reddit refuses), rif behaves as it did before.

No settings.

How it works: a hook at the top of rif's link router resolves `/r|u|user/{sub}/s/{code}` links on a background thread (HEAD request, redirects followed by hand, tracking parameters removed) and calls the router again on the main thread with the result. A second hook at the start of `MainActivity`'s VIEW-intent handling does the same for `/r/` links from other apps, then starts a fresh `MainActivity` on the post and finishes the blank one.

## Fix history import

Makes **Settings → Backup → Import settings** restore the history from a backup made with **Export settings and history**. Imported history is added to the current history; nothing is removed.

rif's export puts the history (`browserhistory.db`) in the backup, but its import skips it, so the history was silently lost. The history decides which posts show as read (dimmed titles), so after moving to a new phone every post you had opened looked unread. With this patch those posts show as read again. Posts that are already in the current history are left as they are; the rest are added, with their last-opened time. A backup made with plain **Export settings** has no history, and importing it works as before. If the history in a backup can't be read, the rest of the import still goes through.

Contributed by Arnaumes. It replaces their separate "Import history" patch, which isn't needed with this bundle; using both is harmless, because both only add missing history.

No settings.

How it works: a hook in rif's import task, right after it reads each zip entry's name, calls the extension. For `browserhistory.db` the extension copies the entry to a temporary file in the cache folder (at most 512 MB, and it must start with the SQLite header), opens it read-only through its own connection, and adds every URI that rif's history doesn't have yet, in one transaction through rif's own database helper (so it never fights rif for the lock). It then tells rif's screens the history changed and deletes the copy. Any failure is logged and ignored, and rif's own import carries on. The extension is based on Arnaumes' patch ([github.com/Arnaumes/revanced-rif-import-history](https://github.com/Arnaumes/revanced-rif-import-history), GPL-3.0).

## Repair broken gallery images

Shows the images of Reddit galleries that fail to load with "Error loading gallery" because Reddit marks some of their images as failed.

For some galleries Reddit sends `{"status":"failed"}` with no size for every image, and rif rejects the whole gallery when any image has no size. The images themselves are still on `i.redd.it`. This patch finds each failed image there (a quick lookup per file type, the last type that worked first), gives rif a normal entry for it, and drops images that can't be found. A gallery with nothing wrong is not touched and makes no lookups. If no image can be found, rif shows its usual error.

No settings.

How it works: a hook at the start of rif's gallery response parser (which runs on a background thread) reads the JSON, and for a gallery with an image that has neither a source size nor a preview, rewrites it before rif parses it. Any failure hands rif the original response.

## Keep reply box above keyboard

Keeps the text you type in the reply box above the on-screen keyboard.

rif's reply box is a floating dialog as tall as its text. The box doesn't learn that the keyboard is open, so after a few lines it grows down behind the keyboard and the text you are typing disappears (pulling down the notification shade and back sometimes refreshed it). This patch keeps the box in the space above the keyboard, where it scrolls instead of growing. The box looks the same; only the comment reply box is changed (modmail dialogs are not).

No settings.

How it works: a hook right after rif's dialog base class sets the dialog window's soft input mode (in `onStart`) calls the extension, which, for a dialog containing both the `scrollview_frame` and `quote_parent` views (only the comment reply box has both; the modmail dialogs share `scrollview_frame`), sets the window's height to `MATCH_PARENT` (keeping its width) and, on every layout pass, sets the bottom padding of the dialog's content view to the height the keyboard covers (from `getWindowVisibleDisplayFrame`). rif's own layout (the box centred in a full-size root, its contents in a `ScrollView`) then caps and scrolls the box above the keyboard.

## Fix broken markdown links

Shows links like `[name](u/name)` as links instead of raw text.

Reddit's markdown renderer rejects a link whose target has no scheme and no leading slash, such as `[Spez](u/spez)` or `[the sub](r/AskReddit)`. It sends the source as plain text with only the `u/…` and `r/…` pieces underlined, so rif shows the brackets and parentheses. (New Reddit shows these as links, so authors don't notice.) This patch turns each such link into its label, as one link to the user or subreddit, which opens like rif's other username and subreddit links and appears once in the links list. Only `u/name` and `r/name` targets (with or without a slash in front or behind) are repaired; other targets, such as `[x](example.com)`, and text in code are left alone. Works in comments, text posts and messages.

No settings.

How it works: a hook in rif's body renderer, after the body's HTML becomes styled text and before rif turns its link spans into tappable links, calls the extension. It finds `[label](target)` where the target is `u/name` or `r/name` and Reddit has underlined the target (which also excludes code), replaces the whole text with the label, drops the underlines inside it and puts one link on the label. Any failure leaves the body as it was.

## Hide blocked mod comments

Adds a setting to hide distinguished (mod and admin) comments from blocked users.

rif already hides a blocked user's comments, but not a distinguished one such as a moderator bot's pinned comment. With **Hide blocked users' mod comments** on (Settings → ReVanced → Blocked users; off by default), those comments disappear too, together with the replies under them, like rif's own blocked-user filter. A comment opened on its own (its permalink) and comments in subreddits you moderate still show.

How it works: Reddit does not mark a blocked mod's comment as a blocked author's; it sends `unrepliable_reason: NEAR_BLOCKED` instead. A hook in rif's blocked-comment filter asks the extension first, passing that flag and the `distinguished` value. When the setting is on and both apply, rif goes straight to its own permalink and moderator checks.

## Reddit image hosting

Uploads images, GIFs and videos for posts to Reddit instead of imgur, and posts them as native image and video posts.

rif's Image tab on the Submit screen uploaded to imgur, which no longer works for everyone. With this patch the tab (renamed "media") works as before (pick, confirm, progress, submit), but the file goes to Reddit's own hosting and the post is a real image post (`i.redd.it`) or video post (`v.redd.it`), not a link. The picker offers images and videos; the confirm dialog and the form's thumbnail show a frame of a video. Images over 20 MB are still shrunk first; GIFs and videos are never resized or re-encoded (Reddit's own size limit applies; videos up to 1 GB). A video's thumbnail is its first frame, uploaded as a second file. The tab's texts no longer mention imgur. One file per post. If the subreddit doesn't allow that kind of post, Reddit's message is shown and the form stays filled.

Comment replies get an image button too: in the formatting bar (when "show formatting" is on), after the link button. It picks a PNG, JPEG, WebP or GIF, and puts a marker like `![img](photo.png)` in the text, as its own paragraph, where you can move or delete it. When the reply is sent the file is uploaded to Reddit (a still image over 20 MB is shrunk; a GIF is never re-encoded, 100 MB at most) and the comment is posted with the image inline; everything else in the text keeps its formatting. Reddit allows one image or GIF per comment, so a second pick is refused. The button is not in private-message replies and edits, and a subreddit that doesn't allow images or GIFs in comments gets a short message instead of the picker. If anything fails the text stays in the dialog.

No settings.

How it works: the picker and preview decoder get small hooks, then four more. The picker's intent also accepts `video/*`; the preview decoder returns a frame for a video (and rotation is skipped for videos). The body of rif's imgur uploader task is replaced by a call into the extension, which asks Reddit for an upload lease (`/api/media/asset.json`, with rif's own sign-in token), posts the file to it and returns the asset URL for rif's URL field. The 10 MB checks (the confirm branch, the dialog's warning, the size state and the resize target) become 20 MB, and for GIFs and videos "needs resize" is always false. A video is streamed from the device as it is; its bucket comes from the lease. `SubmitTask`'s form parameters turn a link post to one of our asset URLs into `kind=image`, or `kind=video` with `video_poster_url` for a video; an asset that was already posted once (a retry after an error) is uploaded again (video and poster), because a reused asset makes a broken post. Reddit answers an image or video post with no post in it, so the reply is read, the user's newest posts are polled for the `i.redd.it` / `v.redd.it` id (up to 30 s, videos 60 s; for a video also the same title posted since) and rif gets a normal reply with the new post's address.
