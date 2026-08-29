# Fossify Messages
<img alt="Logo" src="graphics/icon.webp" width="120" />

<a href='https://play.google.com/store/apps/details?id=org.fossify.messages'><img alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png' height=80/></a> <a href="https://f-droid.org/packages/org.fossify.messages/"><img src="https://fdroid.gitlab.io/artwork/badge/get-it-on-en.svg" alt="Get it on F-Droid" height=80/></a> <a href="https://apt.izzysoft.de/fdroid/index/apk/org.fossify.messages"><img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png" alt="Get it on IzzyOnDroid" height=80/></a>

Fossify Messages is your trusted messaging companion, designed to enhance your messaging experience in various ways.

**📱 STAY CONNECTED WITH EASE:**  
With Fossify Messages, you can effortlessly send SMS and MMS messages to stay connected with your loved ones. Enjoy SMS/MMS based group messaging and express yourself with photos, emojis, and quick greetings.

**🚫 BLOCK UNWANTED MESSAGES:**  
Take control of your messaging experience with a robust blocking feature, easily preventing unwanted messages, even from unknown contacts. You can also export and import blocked numbers for hassle-free backup. Additionally, customize your experience by preventing messages with specific words or phrases from reaching your inbox.

**🔒 EFFORTLESS SMS BACKUP:**  
Say goodbye to worries about losing important messages. Fossify Messages offers convenient SMS backup functionality by allowing you to export and import your messages. This feature ensures that you can easily switch devices without losing your valuable conversations.

**🚀 LIGHTNING-FAST AND LIGHTWEIGHT:**  
Despite its powerful features, Fossify Messages boasts a remarkably small app size, making it quick and easy to download and install. Experience speed and efficiency while enjoying the peace of mind that comes with SMS backup.

**🔐 ENHANCED PRIVACY:**  
Customize what appears on your lock screen for added privacy. Choose to display only the sender, message content, or nothing at all. Your messages are in your control.

**🔍 EFFICIENT MESSAGE SEARCH:**  
Say goodbye to endless scrolling through conversations. Fossify Messages simplifies message retrieval with a quick and efficient search feature. Find what you need, when you need it.

**🌈 MODERN DESIGN & USER-FRIENDLY INTERFACE:**  
Enjoy a clean, modern design with a user-friendly interface. The app features a material design and a dark theme option, providing a visually appealing and comfortable user experience.

**🌐 OPEN-SOURCE TRANSPARENCY:**  
Your privacy is a top priority. Fossify Messages operates without requiring an internet connection, guaranteeing message security and stability. Our app is completely free of ads and does not request unnecessary permissions. Moreover, it is fully open-source, providing you with peace of mind, as you have access to the source code for security and privacy audits.

Make the switch to Fossify Messages and experience messaging the way it should be – private, efficient, and user-friendly. Download now and join our community committed to safeguarding your messaging experience.

➡️ Explore more Fossify apps: https://www.fossify.org<br>
➡️ Open-Source Code: https://www.github.com/FossifyOrg<br>
➡️ Join the community on Reddit: https://www.reddit.com/r/Fossify<br>
➡️ Connect on Telegram: https://t.me/Fossify

<div align="center">
<img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/1_en-US.png" width="30%">
<img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/2_en-US.png" width="30%">
<img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/3_en-US.png" width="30%">
</div>

---

## Fork changes (Yet-Another-Messages-App)

- **Glossy "gel bubble" message theme** - **on by default** (Settings >
  "Glossy gel bubble theme", still a real toggle for anyone who wants the
  flat style back) - renders message bubbles with a top-to-bottom gradient,
  a darker rim, and a soft specular highlight near the top, instead of a
  flat fill. Built entirely from `GradientDrawable`/`LayerDrawable`
  (`extensions/GelBubble.kt`) - no bitmap assets, so it works at any bubble
  size and reflows correctly with variable message lengths. Uses Fossify
  Commons' own `Int.lightenColor()`/`Int.darkenColor()` (confirmed against
  the real Commons source, not reimplemented) to derive the gradient and
  rim tones from a single base color.

  Sent bubbles still use the user's chosen primary color - this feature
  changes how that color renders, not which color gets used, so existing
  color customization stays intact. Received bubbles previously used a
  semi-transparent neutral gray (`activated_item_foreground`); replaced
  with a vivid, opaque sky-blue/teal (`gel_bubble_received_color`,
  `#0EA5E9`) for the gel theme specifically, since the original gray reads
  as utilitarian rather than "pretty." **Now user-customizable** too -
  Settings has a "Received bubble color" row with a real color picker,
  defaulting to that same teal so nothing changes until it's actually
  touched. Because that color can be arbitrary now, not just a fixed
  theme-adaptive gray, its text can't safely rely on the app's fixed theme
  text color - the received bubble computes its own contrast-safe text
  color from whatever color is picked, the same way the sent bubble
  already does from the primary color, so picking a very light or very
  dark received color doesn't create a readability problem in either
  light or dark theme. The flat (non-gel) style is untouched and still
  uses the original gray exactly as before, for anyone who turns the gel
  theme off.

- **Bubble hue slider - shifts both colors together.** A quick "shift the
  whole conversation's color mood" `SeekBar` (0-360°, default 0), separate
  from and complementary to the two precise color pickers above - a hue
  slider can't out-precision an exact color picker for one already-
  arbitrary color, so this exists for fast coordinated shifts, not fine
  control. Applied via real `Color.colorToHSV`/`HSVToColor` inside
  `createGelBubbleDrawable()` itself, so every caller stays in sync
  automatically. Fixed a real correctness issue while wiring it in:
  `ThreadAdapter` was computing each bubble's text contrast color from the
  pre-shift color while the bubble rendered the shifted one - hue rotation
  preserves saturation/value exactly, but perceived brightness isn't
  purely a function of value (yellow reads brighter than blue at the same
  value), so a shift could actually create a text/background readability
  mismatch, not just a theoretical one. Both sent and received contrast
  colors now go through the same effective-color function the drawable
  itself uses. Live preview in Settings renders both bubbles with the
  real `createGelBubbleDrawable()`, not an approximation.

  Kept the same asymmetric tail-corner shape as the existing
  `item_sent_background`/`item_received_background` drawables (sharp
  bottom-right for sent, sharp bottom-left for received), so this reads as
  a finish change, not a shape change.

  **Not verified on a real device** - reasoned from the actual
  `GradientDrawable`/`LayerDrawable` API contracts and confirmed this app's
  `minSdk` (26) comfortably covers every API used (`setLayerSize`/
  `setLayerGravity` need API 21), but not confirmed against a live render,
  since this environment has neither a device nor an emulator.

- **Scope note on theming more broadly:** this is one concrete, finished
  feature for this app. Extending "pretty, customizable themes" further -
  a broader theme-picker system, or gel/gradient treatments in other
  Yet-Another apps - is real, separate design and implementation work per
  app. Contacts' contact-list avatars got the same gel treatment as a
  second, similarly-scoped piece of work - see that app's own README.

- **Bugfix pass: investigated, no changes made.** Checked for the
  ViewPager-tab-recycle leak pattern found in Yet-Another-Voice-Recorder -
  doesn't apply here, this app navigates via real Activities
  (`ThreadActivity`, `MainActivity`), not a `PagerAdapter`, and
  `ThreadActivity`'s own `EventBus` register/unregister is correctly
  paired to real `onCreate()`/`onDestroy()`. Checked conversation search
  for the same missing-debounce issue found in Contacts - this one is
  already well-implemented: properly backgrounded via
  `ensureBackgroundThread`, plus a manual stale-response guard
  (`text == lastSearchedText`) that discards out-of-order results from a
  superseded search. Checked `notifyDataSetChanged()` usage across the
  conversation list adapters - the two in `BaseConversationsAdapter` are
  both explicitly `@SuppressLint("NotifyDataSetChanged")`-annotated by the
  original maintainers for genuinely uniform changes (font size, an
  infrequent draft-map update with its own change guard), and the actual
  hot path (`updateConversations()`) already correctly uses `submitList()`/
  `DiffUtil`. A third usage in `MainActivity` traces to an infrequent,
  user-action-triggered refresh callback, not an auto-refresh loop -
  didn't find strong evidence it's a real problem worth touching.

- **Second bugfix pass: found and fixed a real one.** Different code paths
  than the first pass - manual `Cursor`/receiver/`Handler`/coroutine-scope
  resource management (all clean: cursors consistently use `.use {}`, no
  unpaired `registerReceiver`, no retained `Handler` references, no
  `GlobalScope` misuse) - then image attachment handling, where
  `ImageCompressor.loadBitmap()` decoded whatever the user attached at
  full native resolution with no bounds checking or `inSampleSize` at all,
  in a function that's only ever reached when the file is *already* known
  to be larger than the target compress size - meaning it's routinely
  handed modern smartphone photos (12-100+ MP is normal now) and can
  allocate several hundred MB to over a gigabyte decoding one, risking a
  genuine `OutOfMemoryError` crash before compression even starts. Fixed
  by reusing `decodeSampledBitmapFromFile()` - already-correct, already
  in this file, previously only exercised on retry passes - on the first
  decode too, capped at a 2048px long edge. Also stopped
  `determineImageRotation()` making a redundant full-size `Bitmap` copy
  when EXIF orientation needs no rotation at all. Confirmed reachable, not
  theoretical: traced to `AttachmentsAdapter`, the real
  attach-a-photo-to-a-text path.

## Suggested features (not implemented - suggestions only, with difficulty)

Grounded in what's actually in this codebase already (checked, not guessed) -
so this skips things that exist (scheduled messages, per-app color
customization, message search) and things SMS/MMS genuinely can't support
(see the two flagged Infeasible below).

- **Easy: dark/light gel palette variant.** `GelBubble.kt` derives every
  tone from one base color already - a second palette style (e.g. more
  saturated vs. more muted) is a parameter on the same function, not new
  architecture.
- **Easy: gradient conversation background.** Same `lightenColor`/
  `darkenColor` technique the gel bubbles already use, applied to the
  thread's `RecyclerView` background instead of each bubble. Direct
  extension of code that already exists and is already verified against
  the real Commons source.
- **Easy-moderate: quick reply / canned response chips.** A
  Settings-managed list of short strings, shown as suggestion chips above
  the keyboard in `ThreadActivity`. No new permissions, no new data model
  beyond a simple string list in `Config`.
- **Moderate: message reactions (tap-and-hold, local emoji reaction).**
  Needs a new small table or column keyed by message id, plus a reaction
  badge overlaid on the bubble in `ThreadAdapter`. Fully local, no new
  permissions - the real cost is the DB migration and the overlay
  positioning on a bubble that already has custom shape/gradient.
- **Moderate: per-contact bubble color.** Currently one global received
  color (or one gel base color). Making it per-conversation means a new
  address→color map (small DB table or a JSON-backed pref) rather than a
  single `Config` value, plus a color-picker entry point from the
  conversation details screen. More data-model work than UI work.
- **Moderate: recurring scheduled messages.** `ScheduleMessageDialog` and
  `ScheduledMessageReceiver` already handle one-shot scheduling via
  `AlarmManager` - extending that to "every day at 9am" is a real but
  bounded change to existing, working scheduling code rather than new
  infrastructure.
- **Moderate: export a conversation as a shareable image/PDF.** Needs
  either Android's `PdfDocument` API or rendering the bubble list to a
  bitmap - genuinely more work than it looks because gel bubbles make
  "just draw the existing views" trickier than a flat design would.
- **Hard / likely infeasible: typing indicators.** SMS/MMS has no
  protocol-level concept of "the other person is typing" - that only
  exists in RCS via carrier-operated Jibe/Google infrastructure that
  third-party apps can't access. Not something to half-implement with a
  fake/local-only indicator that doesn't reflect the other person's
  actual state.
- **Hard / likely infeasible: read receipts.** Same root cause as typing
  indicators - real read receipts need RCS or carrier-specific delivery
  APIs not exposed to third-party SMS apps. What *does* already exist and
  work here is SMS delivery reports (`SmsSender`'s existing sent/delivered
  status intents) - that's a different, already-implemented thing, not
  the same as "they've read it."
