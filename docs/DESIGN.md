# FastDrop design direction: "Signal"

Status: **decided**. This is the design spec for the Android app (phone and Android/Google TV).
A builder can implement it without asking for more design calls. Where this doc and the code
disagree, follow the doc. Sources are listed at the end and cited inline as `[S#]`.

Reading order: §0 concepts considered → §1 diagnosis → §2 direction and tokens → §3 IA →
§4 phone screens → §5 TV → §6 component kit → §7 accessibility → §8 build order.

---

## ⚠ Owner feedback after the first build (overrides anything below)

The owner saw the first Signal build on the phone: **the look is good, keep it.** That means:
- the clean paper / off-black surfaces;
- the 1 px rules;
- the numbered device ports;
- the coloured bottom dock with Receive in ultramarine and Send in orange;
- the solid colour plates.

Do **not** redesign it again. Only the things listed below need rearranging and modifying ("too minimal, options gone"):

1. **Restore the missing options on Home.**
   - Add a **Quick send** row: Apps, Photos, Videos, Music, Files, Text.
   - Each tile is a compact solid plate in its category colour, with an icon and a label underneath.
   - Each tile opens the picker on that tab, or the text composer for Text.
   - Place the row near the top, under the device status and above Nearby, so the main options are visible without scrolling.
   - Keep Scan a code and Share with any device as rows.
   - Every feature stays reachable from Home or Send with one tap. Nothing is hidden in menus.
2. **No extra colour.** The owner decided against adding more. Keep colour exactly where the current build has it: the bottom dock, the existing small plates and icons, and the transfer panel states. The Quick send tiles follow the same rule as the existing plates. Don't add coloured panels, tints or extra accents anywhere.
3. **No large empty areas.**
   - The done/transfer sheet must size to its content or use the space well.
   - Show file rows with type plates, plus actions: Open, Share, Show in folder, Send back, Send more.
4. **Polish the dot readout.**
   - Evenly spaced round dots and consistent glyph widths.
   - Unlit dots very subtle.
   - Digits that read cleanly.
   - The unit and the % aligned to the readout.

---

## 0. Concepts considered

All three concepts follow one rule: **one memorable signature moment (the transfer), and
restraint everywhere else.** They differ in where the personality comes from.

### 0.1 "Signal" (chosen)

An instrument panel with colour-coded controls: a neutral body (warm paper or off-black) and
**six bold, flat colours, each with one job**, the way Teenage Engineering's OP-1 and OP-Z put
saturated colour-coded keys and knobs on a plain chassis [S1]. Colour is used as **solid fills on
big blocks**: the Send/Receive buttons, the transfer panel, the device ports, and the dot-matrix
speed readout. It is never used as thin tinted outlines or gradients. Structure comes from 1 px
rules instead of cards, tabular numerals, and devices shown as numbered "ports". The signature
moment is a big **dot-matrix speed readout**, drawn with Canvas dots on a solid panel in the
transfer's colour (orange while sending, ultramarine while receiving, green when done, red when
failed). It also borrows Nothing OS's restraint with dot-matrix type: use it only where it adds
character, and drop it where it hurts legibility [S2][S3].

```
PHONE                                 TV (960x540 dp canvas)
┌──────────────────────────────┐     ┌──────────────────────────────────────────────┐
│ FastDrop              ⟲  ⚙   │     │  Himanshu's TV            │ Nearby            │
│ Himanshu's S21 FE            │     │  ● Visible on Home Wi‑Fi  │ 01 [▯]Pixel 8   > │
│ ● Visible on Home Wi‑Fi      │     │ ───────────────────────── │ 02 [▱]MacBook   > │
│──────────────────────────────│     │ ███ Send files ███ ORANGE │───────────────────│
│ Nearby                  Scan │     │  Show my QR       (ULTRA) │ Recent            │
│ 01 [▭]Living room TV  Wi‑Fi >│     │  Share with any…  (YELLOW)│ [▪]IMG_2034.jpg   │
│ 02 [▯]Pixel 8         Wi‑Fi >│     │  History                  │ [▪]app-release.apk│
│──────────────────────────────│     │  Settings                 │                   │
│ Recent                   All │     └──────────────────────────────────────────────┘
│ [▪]IMG_2034.jpg   3.2 MB  2m │       transfer overlay: ORANGE panel, ink dots
│──────────────────────────────│        ●●● ●●● . ●●●  MB/s   ▮▮▮▮▮▮▯▯▯ 63 %
│ [██ Receive ██][███ Send ███]│
│   ULTRAMARINE     ORANGE     │   [▭] = port plate filled in the device-type colour
└──────────────────────────────┘
```

- For: the most distinctive of the three, and nothing like the current violet look. Colour is
  confident but always *means* something, so it reads as designed, not decorated. Flat fills and
  1 px rules are the cheapest thing a weak TV GPU can draw, and a solid colour block is visible
  from 10 feet. The readout needs no bundled font.
- Against: harder to execute. Six colours used carelessly would turn back into the old rainbow,
  so §2.3 fixes every colour's job and lists where it may appear. Too many mono labels or dot
  glyphs would turn it into a costume. Dot-matrix legibility is a known risk [S3], so the
  readout is limited to two places (speed and confirmation code) and always paired with plain
  text. Fire TV advises against large warm, saturated areas on TV [S7], so on TV colour fills
  only the focused element and the transfer panel, and backgrounds stay off-black.

### 0.2 "Expressive"

Material 3 Expressive: springy shape morphs, tonal containers, dynamic wallpaper colour on
API 31+. It would sit close to Google's 2025 full-screen Quick Share (Send/Receive tabs, device
cards in a grid, QR button in the corner [S4]).

```
PHONE                                 TV
┌──────────────────────────────┐     ┌──────────────────────────────────────────────┐
│  ( Send )  ( Receive )        │     │  ( Send )  ( Receive )         [QR]          │
│ ╭──────────────────────────╮ │     │ ╭────────╮ ╭────────╮ ╭────────╮              │
│ │ 3 photos · 12 MB      ▾  │ │     │ │  ▭     │ │  ▯     │ │  ▭     │              │
│ ╰──────────────────────────╯ │     │ │ Pixel 8│ │ TV     │ │ Mac    │              │
│ Send to nearby devices   [QR]│     │ ╰────────╯ ╰────────╯ ╰────────╯              │
│ ╭────────╮ ╭────────╮        │     └──────────────────────────────────────────────┘
│ │  (▭)   │ │  (▯)   │        │
│ │ TV     │ │ Pixel  │        │
│ ╰────────╯ ╰────────╯        │
└──────────────────────────────┘
```

- For: feels native, and users already know the pattern from Quick Share.
- Against: generic. It's the look the owner is trying to get away from, just with Google's
  blessing. Without the Material library we would rebuild spring and shape-morph machinery by
  hand, and that's the most expensive motion for old TVs. Dynamic colour exists only on API 31+,
  and TVs mostly don't expose it.

### 0.3 "Paper"

Editorial and calm. A light warm-paper default, large grotesk or serif headlines, devices shown
like a contacts list, almost no chrome, and a warm-ink dark mode.

```
PHONE                                 TV
┌──────────────────────────────┐     ┌──────────────────────────────────────────────┐
│                              │     │                                              │
│  Send something              │     │   Send something                             │
│  to someone nearby.          │     │   to someone nearby.                         │
│                              │     │                                              │
│  Living room TV            → │     │   Pixel 8                                  → │
│  Pixel 8                   → │     │   MacBook                                  → │
│                              │     │                                              │
│  Receive · Scan · Browser    │     │   Show my QR · History · Settings            │
└──────────────────────────────┘     └──────────────────────────────────────────────┘
```

- For: very calm and very readable, with a strong typographic voice.
- Against: a big serif or grotesk has to be bundled (40–120 KB, or 15–40% of the APK) or it
  falls back to Roboto and loses its point. It's weak at showing live state such as speed,
  progress or several devices, which is what this app does. Large light areas glare on TV [S6].

**Decision: Signal.** It's the only concept whose personality comes from the product's job
(moving bytes fast, measured in MB/s) and not from decoration. It costs no bundled font, and it
is the cheapest to render on TV.

---

## 1. Diagnosis: why it reads as "AI-made"

Checked against the common AI-slop tells [S10][S11]. FastDrop has every one of them:

| Tell | Where in FastDrop |
|---|---|
| Purple→blue gradient as identity | `Theme.SEND_A #8B5CF6 → SEND_B #3B82F6` and `ACCENT #7C6CFF`. These are Tailwind's default `violet-500` and `blue-500`, used as-is. The others are Tailwind defaults too: `RECV_B #0EA5E9` sky-500, `WEB_A #F59E0B` amber-500, `CAT_APPS #22C55E` green-500, `CAT_PHOTOS #F472B6` pink-400, `CAT_VIDEOS #FB923C` orange-400, `CAT_MUSIC #A78BFA` violet-400, `CAT_FILES #38BDF8` sky-400, `SUCCESS #34D399` emerald-400. The palette was never chosen. It's the framework default. |
| Colour without meaning | `Theme.kt` defines 16 chromatic colours (3 gradient pairs, 2 accents, 5 category colours, 3 status colours). The home screenshot alone shows violet, blue, teal, cyan, green, pink, orange, purple and amber. The problem isn't that there's colour. The colours are pastel tints and gradients, and none has a consistent job: violet means "brand", "send", "scan" and "focus" all at once. |
| Glassy gradient tiles | `Ui.actionCard` (Send/Receive): a gradient fill, a 1 px white stroke, a coloured shadow from `glow()` at 14 dp elevation, a translucent white circle badge, and `SheenLayout`, which sweeps a light band across the card every ~4 s forever. |
| Emoji-style icon tiles | `Ui.tile` (Quick send: Apps green, Photos pink, Videos orange, Music purple) and every `Ui.row` with an icon put a glyph in a rounded square tinted at 15–16%. |
| All-caps tracked eyebrow labels | `Ui.label()` uppercases and adds `letterSpacing = 0.1f`: "QUICK SEND", "NEARBY ON THIS WI‑FI", "RECENT", "NETWORK DETAILS", "LIVE". |
| Everything in identical rounded cards | `Ui.card` (24 dp radius, gradient fill, stroke, commented "gives the glass feel") wraps every block. Home has 6 stacked cards, and the Send screen has cards inside a card layout. |
| Glows | `Ui.glow()` puts coloured shadows on primary buttons and action cards. `FocusRing` adds a 7 dp violet halo on TV. |
| Decorative radar, orbs, confetti | `Backdrop.kt` draws three drifting aurora blobs at ~30 fps on **every** screen (MainActivity, Send, Receive, WebShare, Picker, Browser). `RadarView` takes 230–300 dp on Send and Receive to repeat the device list below it. `Confetti.kt` fires 90 particles when a transfer finishes. |
| Bouncy, staggered motion | `Theme.SPRING` (Overshoot 1.3) runs on every press release. `Anim.stagger` fades in every Home block one after another on each build, and `Anim.pulse` loops on the searching dot. |
| Heavy display type | `Ui.title` uses `sans-serif-black` with −0.02 tracking. There are 14 text sizes (11.5, 12, 12.5, 13, 13.5, 14, 14.5, 15, 16, 17, 18, 22, 24, 28 sp) and no scale. |
| Vague, generic copy | "Ready to receive" next to a "Ready" chip (said twice), "Show QR, be visible", "Photos, apps, files", "Sent!", "Received!", "Something went wrong", "*X* wants to send you" (sentence cut off). The Home empty state is 31 words. |
| Logo cliché | Lightning bolt in a gradient squircle (`HomeScreen.topBar`). |

Hierarchy problems that go beyond style:

1. **Devices are buried.** On the home screenshot, "Nearby on this Wi‑Fi" is the 6th block and
   sits below the fold. Quick Share, AirDrop, LocalSend and PairDrop all lead with nearby
   devices, because picking a recipient is the decision [S4][S8][S9].
2. **Receive gets a hero card, but receiving is passive.** FastDrop receives whenever it's open.
   The Receive card only leads to QR and Wi‑Fi Direct hosting, a secondary path.
3. **The Quick send tiles scroll sideways and clip "Files"** on a 360 dp phone.
4. **TV devices are drawn in the amber→pink WEB gradient** (`HomeScreen.renderNearby`), so the
   colour carries no meaning.
5. **The app is dark-only** (`styles.xml` hard-codes `#07080D`), with no light theme on phones.
6. **The countdown ring around the avatar** on the offer sheet is decoration that hides how
   much time is left.

---

## 2. Direction: Signal

### 2.1 Principles

1. **Ports, not posters.** The screen is a panel of things you can send to. Every element is
   either a device, a file, a number or an action. No illustration.
2. **Colour-coded, like the OP-1.** Six flat colours, and each has exactly one job (§2.3).
   Orange is always Send, ultramarine always Receive, yellow always the browser, pink always
   text, green always done, red always failed. Learn it once and it holds on every screen,
   phone and TV.
3. **Colour is a fill, never a tint.** Colour comes in solid blocks (buttons, the transfer
   panel, port plates, focused or selected rows) with dark or white text chosen for AA. No 15%
   tints, no coloured outlines, no gradients, no glows.
4. **Neutral body, rules not cards.** Surfaces stay paper or off-black so the colour reads.
   Structure comes from 1 px rules and alignment, not boxes.
5. **Numbers are first-class.** Sizes, speed, ETA and percent use tabular figures and line up.
6. **One signature moment.** The coloured dot-matrix panel appears during a transfer and for
   the confirmation code. Nowhere else.
7. **Nothing moves unless it reports something.** No idle animation.

### 2.2 Theme mode

- **Phone: follow the system.** On API 29+, read `Configuration.uiMode and UI_MODE_NIGHT_MASK`.
  Below API 29 there is no system dark mode, so default to **Light**. Settings → Appearance
  offers *System default / Light / Dark*. Why light matters: phones are used outdoors and in
  daylight, warm paper is easier to read in sun, and a light theme is half of what makes Signal
  look different from the old ink-and-violet UI.
- **TV: always dark.** TVs are watched in dim rooms, and a large bright surface glares [S6]. Hide
  the Appearance setting on TV.
- **QR codes are always black on a white plate**, in both themes, so scanners work.
- **The six colour fills are identical in both themes**, like painted keys on hardware. Only
  the neutrals and the coloured-*text* variants change between light and dark.
- **No dynamic colour.** The fixed colour code is the identity: orange must mean Send on every
  phone. Wallpaper colour would break that, and it exists only on API 31+
  (`android.R.color.system_accent1_*`, which can be read with `ctx.getColor` without Material
  libraries [S13]). Don't use it, not even in the widget.

### 2.3 Palette

#### Neutral body

| Role (token) | Light ("paper") | Dark ("off-black") | Use |
|---|---|---|---|
| `BG` | `#F3F1EC` | `#0F0F0E` | Window and screen background |
| `SURFACE` | `#FBFAF7` | `#191917` | Sheets, dialogs, the docked bottom bar |
| `SURFACE_2` | `#E7E4DD` | `#232321` | Wells: text-offer preview, inputs, the Files category plate, phone pressed state on neutral rows |
| `INK` | `#161513` | `#ECEAE4` | Primary text and icons; TV focus fill for neutral items; toggle-on fill |
| `INK_2` | `#5B5852` | `#A29F98` | Secondary text, meta, section labels, port numbers |
| `INK_3` | `#8C887F` | `#6F6C66` | Disabled text, placeholders at ≥18 sp only, already-present meter segments |
| `LINE` | `#D9D5CC` | `#2B2A27` | 1 px rules and dividers (decorative) |
| `LINE_STRONG` | `#8C887F` | `#6F6C66` | Outlines of controls: secondary button, tag, input, unchecked checkbox |
| `SCRIM` | `#99000000` | `#B3000000` | Behind sheets |
| `QR_PLATE` / `QR_INK` | `#FFFFFF` / `#000000` | same | QR codes only |

#### The colour code: six flat colours, one job each

Fills are **the same hex in both themes**. Text on a fill is either near-black `#161513` or
white `#FFFFFF`, whichever passes AA (§7.1). It never changes with theme.

| Token | Hex (fill) | Name | Its one job | Text/icon on fill (`ON_*`) | When used as text on BG: light / dark |
|---|---|---|---|---|---|
| `C_SEND` | `#FF5A1F` | Signal orange | **Send**: everything outgoing, the brand | `#161513` (5.9:1) | `#C53D0B` / `#FF5A1F` |
| `C_RECEIVE` | `#2E3BFF` | Ultramarine | **Receive**: incoming, being visible, Accept | `#FFFFFF` (6.6:1) | `#2E3BFF` / `#8E97FF` |
| `C_WEB` | `#FFC21A` | Warm yellow | **Share with any device** (browser) | `#161513` (11.3:1) | never as text |
| `C_TEXT` | `#FF5FA2` | Hot pink | **Text, links, clipboard** | `#161513` (6.4:1) | never as text |
| `C_OK` | `#1FAE52` | Signal green | **Done / success / ready** | `#161513` (6.3:1) | `#13753A` / `#1FAE52` |
| `C_DANGER` | `#D7192F` | Alarm red | **Failed / destructive / error** | `#FFFFFF` (5.2:1) | `#D7192F` / `#FF6B6B` |

Why these: they're the OP-1/OP-Z idea (saturated, flat, warm, a little toy-like) and
deliberately not Tailwind's violet/blue/teal. Orange and ultramarine are near-complements, so
Send and Receive can never be confused. Yellow and pink are the "side doors" (browser, text). Green
and red are kept for outcomes only. Red (`#D7192F`, crimson with white text) is pushed away from
orange (`#FF5A1F`, light with dark text), so the text colour on top separates them as well as
the hue does.

Dot shades for the readout panel, one per panel colour: unlit dot = fill mixed 16% toward its
`ON_*` colour.

| Panel | Lit dots | Unlit dots |
|---|---|---|
| `C_SEND #FF5A1F` | `#161513` | `#DA4F1D` |
| `C_RECEIVE #2E3BFF` | `#FFFFFF` | `#4F5AFF` |
| `C_OK #1FAE52` | `#161513` | `#1E9648` |
| `C_DANGER #D7192F` | `#FFFFFF` | `#DD3E50` |

#### Identity colours (devices and content), drawn from the same six

Only **ultramarine, yellow, green and pink** are used for identity. **Orange and red never
are**, so "orange = Send" and "red = failed" stay unambiguous. Identity colour only ever
appears as a **solid square plate behind a glyph** (40 dp phone, 48 dp TV, 6 dp radius,
glyph in the `ON_*` colour). It's never on a button.

| Thing | Plate | Glyph colour |
|---|---|---|
| Phone | `C_RECEIVE` ultramarine | white |
| TV | `C_WEB` yellow | ink |
| Laptop / Mac / PC | `C_OK` green | ink |
| Browser client (Share with any device events) | `C_WEB` yellow | ink |
| Photos | yellow | ink |
| Videos | ultramarine | white |
| Apps | green | ink (real app icons replace the plate when available) |
| Music | pink | ink |
| Text / links | pink | ink ("things you read or hear" share pink) |
| Files / folders | `SURFACE_2` (neutral) | `INK` |

#### Where each colour may appear

| Colour | Allowed uses (and nowhere else) |
|---|---|
| Orange `C_SEND` | Send button (dock), picker Next, "Send to 3 devices", Retry on a failed **send**; the sending transfer panel and its meter; selected ports (multi-select fill) and the phone pressed state of a port; picker selection marks; TV focus on ports and "Send files"; the "waiting for accept" code panel on the sender. |
| Ultramarine `C_RECEIVE` | Receive button (dock); Accept; "Scan sender's QR"; the receiving transfer panel and its meter; the offer sheet's code panel; the "Always accept" checkbox when checked; LED when visible beyond Wi‑Fi (Receive screen open or QS tile on); TV focus on "Show my QR"; phone-type and video-category plates. |
| Yellow `C_WEB` | "Share with any device" entries (Home row plate, TV focus fill), "Start sharing" / "Try again" on the web share screen, the web share live grid's header strip; TV, browser and photo plates. |
| Pink `C_TEXT` | The selected Text tab in the picker and the 4 dp leading block on text fields and wells; the text-offer panel; Copy / Open link on a received text; music and text plates. |
| Green `C_OK` | Transfer panel and strip when done; Install / Open after a finished receive; ✓ glyphs on finished file and device rows; LED "ready on Wi‑Fi"; QR scanner brackets on success; laptop and app plates. |
| Red `C_DANGER` | Transfer panel and strip when failed; Receive status block when hosting fails; error lines ("!" glyph + text in the `C_DANGER` text tone); destructive confirm buttons (Remove trusted device, Clear selection, Stop sharing); LED when the server can't receive. |

Rules:
- **One filled button per colour per screen.** A screen may show two filled buttons only when
  they are different jobs (Home: Receive + Send). Everything else is outlined or quiet.
- **Colour is never the only cue.** Every colour carries a word or glyph: Send/Receive labels,
  ✓, "!", and a device-type glyph on its plate.
- **No tints.** No `alpha()` on a colour token, ever. If something needs to be quieter, make it
  neutral.
- **Phone budget:** apart from the transfer panel, colour fills cover ≤ ~15% of a screen. That's
  enough for the dock buttons plus the port plates.

### 2.4 Typography

**No bundled font.** System `sans-serif` (Roboto on almost every phone and TV, which is also the
Android TV default [S5]). The instrument character comes from three free sources:
- **Tabular figures** on every number: `TextView.fontFeatureSettings = "tnum"` (API 21+;
  Roboto supports it, and OEM fonts that don't just ignore it).
- **`Typeface.MONOSPACE`** (Droid Sans Mono, in the system image) for short technical strings
  only: port numbers `01`, IP addresses, the network name and password, and file-size columns in
  the transfer list.
- **The dot-matrix readout** (§2.9), drawn on Canvas with zero font bytes.

Why not bundle JetBrains Mono or Space Grotesk: a useful subset costs 25–60 KB, or 8–20% of a
~300 KB APK, for something the readout already provides. Revisit only if the APK grows past
1 MB for other reasons.

Weights: `sans-serif` (400) and `sans-serif-medium` (500) only. **Remove `BOLD` and `BLACK`.**
Hierarchy comes from size and colour, not weight.

Case: **sentence case everywhere. No uppercase, no letter-spacing.** The only "technical" text
is digits in `MONOSPACE`, which have no case.

| Style | Phone size/line | TV size/line | Face | Colour | Use |
|---|---|---|---|---|---|
| `T_HERO` | 24/30 | 32/40 | medium | INK | This device's name on Home; finished-transfer title |
| `T_TITLE` | 20/26 | 26/32 | medium | INK | Screen header title, sheet title |
| `T_ROW` | 16/22 | 18/24 | medium | INK | Row titles (device, file, setting) |
| `T_BODY` | 15/21 | 16/22 | regular | INK | Sentences, descriptions, text-offer preview |
| `T_META` | 13/18 | 14/20 | regular + tnum | INK_2 | Row subtitles, sizes, times, status lines |
| `T_SECTION` | 13/18 | 15/20 | medium | INK_2 | Section labels ("Nearby", "Recent") |
| `T_MONO` | 12/16 | 14/18 | MONOSPACE | INK_2 | Port numbers, IP, SSID, tags |
| `T_BUTTON` | 15/20 | 17/22 | medium | per button | Button labels |
| `T_FIGURE` | 28/32 | 36/40 | regular + tnum | INK | Big numbers next to the readout ("63 %") |

TV minimums: body ≥16 sp and nothing below 14 sp. Fire TV sets 14 sp as the floor [S7], and the
10-foot guidance says "when in doubt, go larger" [S6].

Line height on API 21–27: there is no `setLineHeight`. Add a helper
`Ui.lineHeight(tv, sp)`: on API 28+ call `tv.lineHeight = px`, otherwise
`tv.setLineSpacing(px - tv.paint.getFontMetricsInt(null), 1f)`. Keep `includeFontPadding = false`.

### 2.5 Spacing

4 dp base. Tokens: `S1=4, S2=8, S3=12, S4=16, S5=24, S6=32, S7=48`.

| | Phone | TV |
|---|---|---|
| Side gutter | **16** (was 20) | **48** [S5b] |
| Top/bottom safe | system insets | **27** [S5b] |
| Row min height | **56** (one line), 64 (two lines) | **64** |
| Gap between sections | 24 | 32 |
| Rule to section label | 12 above the label, 8 below | 16 / 8 |
| Icon to text in a row | 16 | 20 |

Rules are **full-bleed**: they run from screen edge to screen edge, and row content is inset by
the gutter. To do this, `Ui.scrollPage` stops padding horizontally, and rows and sections apply
the gutter themselves.

### 2.6 Shape, borders, elevation

- Radii: **0** for rows, sections, rules, thumbnails in grids and the transfer panel (a
  full-bleed colour block). **6 dp** (`R_CTRL`) for buttons, inputs, tags, colour plates,
  checkboxes, the code panel, TV focus fills and the snackbar. **12 dp** (`R_SHEET`) only on the top corners of bottom sheets and on all
  corners of TV centre overlays. **Fully round only for:** the 8 dp status LED, the readout dots
  and the toggle knob.
- Rules: `LINE`, thickness `max(1, (density / 2).toInt())` physical px. That's 1 px up to xxhdpi
  and 2 px at xxxhdpi, so the hairline stays crisp.
- **No elevation and no shadows anywhere.** Delete `glow()`, set `elevation = 0` on the
  floating bar, and remove `translationZ` on focus. Sheets are separated by the scrim plus a 1 px
  `LINE` on their top edge. This also saves overdraw on TVs.
- No gradients anywhere. `GradientDrawable` is used only for a solid fill or stroke.

### 2.7 Iconography

- Keep the existing 24 dp vector set in `res/drawable`. One weight, outline style, square caps.
- Two kinds of icon, and nothing in between:
  - **Bare** (utility): `INK` in headers, `INK_2` as a row's leading descriptor ("Scan a code",
    the settings rows). No badge, no circle, no tint.
  - **On a solid plate** (identity or job): devices, content categories, and the "Share with any
    device" entry. A 40 dp (TV 48 dp) square, 6 dp radius, **solid** colour from the identity
    table in §2.3, glyph 22 dp in that colour's `ON_*`. This replaces the gradient-circle
    `Ui.avatar` and the 15%-tint badges. The difference from the old "emoji tiles" is that the
    fill is flat and full-strength, the colour has one meaning, and plates appear only on
    devices and content, never on settings or utility rows.
- When a row is filled with colour (selected, pressed, TV-focused), its plate **inverts**: plate
  becomes the row's `ON_*` colour and the glyph takes the row's fill colour. Two colours never
  sit edge to edge.
- Sizes: 24 dp in headers and on TV, 20 dp in phone rows and inside plates on phone (TV 24),
  16 dp inside tags.
- Remove `ic_bolt` from the app UI. Launcher icon and TV banner are covered in §8, step 12.
- Never use emoji in UI copy.

### 2.8 Motion

Durations: `D_TAP = 90 ms` (press, toggle, check), `D_STATE = 150 ms` (text and row
enter/exit, tab underline, fades), `D_SHEET = 220 ms` (sheet in), `D_SHEET_OUT = 180 ms`.
Easing: enter = standard decelerate `PathInterpolator(0f, 0f, 0f, 1f)`, exit = standard
accelerate `PathInterpolator(0.3f, 0f, 1f, 1f)`, move = standard `PathInterpolator(0.2f, 0f, 0f, 1f)`
(M3 standard curves [S12]). **Delete `SPRING`/Overshoot.** No springs, no scale bounce.

What may animate:
- Sheet slide-up (phone) or fade (TV), plus the scrim fade.
- A row appearing or disappearing: alpha 0→1 plus 8 dp rise (was 16 dp at 420 ms).
- Screen push/pop: a 150 ms crossfade with an 8 dp horizontal shift on phone, crossfade only on TV.
- The meter fill: it steps per update with no tween.
- **"Scan ticks"** (searching indicator): three 6 dp squares that light in sequence, 300 ms each.
  This is the **only looping animation in the app**. It stops as soon as one device appears or
  the screen hides.
- The readout: the value redraws at 4 Hz with no tween between values.

What must not animate: backgrounds (no Backdrop), idle cards (no sheen), numbers (delete
`Anim.countTo`, which counts sizes up), list reorders (instant), TV focus (it swaps instantly,
§5.3), press scale (delete it; press feedback is a fill change), Home build (delete
`Anim.stagger`).

Animations off (`Theme.animationsOn == false`): every transition is instant, scan ticks show
all three squares at `INK_3` with the text "Looking…", and the offer countdown is text only.
Colour changes (panel state, focus fill, selection fill) are always instant, with or without
animations. The
readout still updates, because it's data and not decoration.

Haptics (phones only; `performHapticFeedback` already respects the system setting):
- Press on a port or primary button: `VIRTUAL_KEY`.
- Toggle a selection (picker, multi-select, checkbox): `CLOCK_TICK`.
- Transfer done: `CONFIRM` (API 30+), else `VIRTUAL_KEY`.
- Failed or declined: `REJECT` (API 30+), else `LONG_PRESS`.
- QR scanned: `CONFIRM`.
- Nothing on progress ticks, and nothing when devices appear.

### 2.9 The signature: dot-matrix readout

A `DotReadout : View` that draws a short numeric string as dots on a **solid colour panel**.
The panel is the transfer's colour, so the state reads from across the room:

| State | Panel fill | Lit dots | Unlit dots |
|---|---|---|---|
| Sending, or the sender's waiting-for-accept code | `C_SEND #FF5A1F` | `#161513` | `#DA4F1D` |
| Receiving, or the receiver's offer code | `C_RECEIVE #2E3BFF` | `#FFFFFF` | `#4F5AFF` |
| Done (average speed) | `C_OK #1FAE52` | `#161513` | `#1E9648` |
| Failed (last speed, frozen) | `C_DANGER #D7192F` | `#FFFFFF` | `#DD3E50` |

The panel colour switches instantly, with no cross-fade: the change of state *is* the moment.

- Glyph grid **5×7 dots**. Character cell = 5 dot pitches wide plus 1 pitch gap. `.` = 1 pitch
  wide plus 1 gap, dot on the bottom row. `-` = middle row, columns 1–4.
- Sizes: phone pitch **9 dp**, dot diameter **7 dp** (a digit is 45×63 dp). TV pitch **12 dp**,
  dot **9 dp**. Code readout on phone: pitch 8 dp, dot 6 dp.
- The panel pads 2 pitches on each side and shows **all** cells, unlit dots included. Drawing
  the unlit dots is what makes it read as an instrument and not a gimmick.
- Characters: digits `0–9`, `.`, `-`, space. **No letters.** The unit ("MB/s", "KB/s") is
  regular `T_ROW` text in the panel's `ON_*` colour, inside the panel at the right, aligned to
  the bottom of the digits. The `CRC` tag sits above it, outlined in the `ON_*` colour.
- Speed format: always ≤ 4 cells. `< 10` → `8.42`; `< 100` → `46.2`; `≥ 100` → `146`.
  Switch to KB/s below 1 MB/s. Smooth with an EMA over ~2 s and redraw every 250 ms.
- Before the first byte: `--.-`. Failed: the last value stays on the red panel. Done: the
  average speed on the green panel.
- Cost: 4 cells × 35 dots = 140 `drawCircle` calls per redraw, at 4 Hz. Cache the `Paint`
  objects and allocate nothing in `onDraw`.
- Accessibility: `contentDescription = "46.2 megabytes per second"`. Don't make it a live
  region (§7).

Glyph bitmaps (row by row, `#` = lit). Store each as 7 `Int`s of 5 bits:

```
0      1      2      3      4      5      6      7      8      9
.###.  ..#..  .###.  #####  ...#.  #####  ..##.  #####  .###.  .###.
#...#  .##..  #...#  ...#.  ..##.  #....  .#...  ....#  #...#  #...#
#..##  ..#..  ....#  ..#..  .#.#.  ####.  #....  ...#.  #...#  #...#
#.#.#  ..#..  ...#.  ...#.  #..#.  ....#  ####.  ..#..  .###.  .####
##..#  ..#..  ..#..  ....#  #####  ....#  #...#  .#...  #...#  ....#
#...#  ..#..  .#...  #...#  ...#.  #...#  #...#  .#...  #...#  ...#.
.###.  .###.  #####  .###.  ...#.  .###.  .###.  .#...  .###.  .##..
```

The slashed zero is deliberate. On the code readout it keeps `0` and `8` apart from across a
room.

### 2.10 Copy and voice

Rules:
1. **Name the thing.** Use the device name, the network name, the file count and the size.
   "Visible on Home Wi‑Fi", not "Ready".
2. **Sentence case, no exclamation marks, no "Oops", no emoji.**
3. **Lead with the outcome.** Put status before instructions. An empty state is ≤ 12 words,
   plus one optional action.
4. **Say what happened and what happens next** on errors: cause, then remedy.
5. **Digits for numbers**, a narrow no-break space before units ("3.2 MB", "63 %"), and the
   real ellipsis "…" only for ongoing states.
6. **Verbs on buttons**, describing exactly what happens: "Send", "Accept", "Retry", "Copy
   link". Never "OK", except to close an informational dialog.
7. Keep README terms the user may already know: **Send**, **Receive**, **Share with any device**,
   **Show my QR**, **History**.

| Before | After |
|---|---|
| "Ready to receive · auto‑accept on" + chip "Ready" | "Visible on Home Wi‑Fi · trusted devices send without asking" |
| "Network · 10.151.55.247" | "Home Wi‑Fi · 10.151.55.247" (IP in `T_MONO`) |
| Card "Send / Photos, apps, files" | Dock button "Send" |
| Card "Receive / Show QR, be visible" | Dock button "Receive" |
| "Scan QR code / Join a device that shows its code" | "Scan a code" |
| "Share with any device / iPhone, PC — no app needed" | "Share with any device / Opens in their browser. No app needed." |
| "Looking for devices… Open FastDrop on the other device. Devices on the same Wi‑Fi or hotspot appear here by themselves." | "Looking on Home Wi‑Fi…" / meta: "Open FastDrop on the other device." |
| "*X* wants to send you" / "3 files · 48 MB" | "*Pixel 8* wants to send 3 photos" / "48 MB" |
| "Expires in 60 s" | "Closes in 60 s" |
| "Sent!" | "Sent to Living room TV" |
| "Received!" | "Saved 12 files to Download/FastDrop" |
| "Transfer failed" / "Something went wrong" | "Couldn't finish sending to Pixel 8" / "Connection lost. Retry continues from 63 %." |
| "Tap to see progress" | "Sending to Pixel 8 · 63 % · 0:14 left" |
| "Nothing received yet. Files land in Download/FastDrop." | "Nothing yet. Received files go to Download/FastDrop." |
| "Auto‑accept / Receive files without asking…" | "Accept files from: Trusted devices" |

---

## 3. Information architecture

### 3.1 What Home is for

Home answers three questions in order: **who can I send to, am I reachable, and what just
arrived.** It's **device-first**, like Quick Share's Send tab, AirDrop's recipient row,
LocalSend and PairDrop [S4][S8][S9], because choosing the recipient is the decision and files can
be picked afterwards. The verbs live in a docked bar at the thumb.

Hierarchy, top to bottom:
1. Identity strip: app name and header icons (History, Settings); this device's name; status
   line with LED.
2. **Nearby**: ports (devices on this Wi‑Fi, then Wi‑Fi Direct, then QR). The "Scan" action sits
   on the section label row (phone with camera only).
3. **Other ways**: Scan a code, Share with any device. Two rows, no more.
4. **Recent**: last 3 History items, with "All" → History.
5. Docked bar: **[Receive] [Send]**, two solid blocks, ultramarine and orange. That's the
   app's colour code shown on the first screen.
6. Transfer strip (only while a transfer runs, or while one is hidden): docked above the bar.

Deleted from Home: the Send/Receive hero cards, the Quick send tiles (the picker remembers the
last tab instead), and the separate device card.

### 3.2 Primary flows

```
SEND (files)    Home ─ Send ─▶ Picker (tabs; Next) ─▶ Choose devices ─ tap port ─▶ Transfer sheet
SEND (to port)  Home ─ tap port ─▶ Picker ─ Next ─▶ Transfer sheet          (skips the chooser)
SEND (several)  Choose devices ─ "Select" ─ check 2+ ports ─ "Send to 3 devices" ─▶ Transfer sheet (multi)
SEND (text)     Picker ─ Text tab ─ type or "Paste" ─ Next ─▶ Choose devices …
                Android share sheet (text/URL) ─▶ Choose devices, with a text preview at the top
SHARE INTENT    Android share sheet (files) ─▶ Choose devices (selection pre-filled)
RECEIVE         Passive: an offer sheet appears on any screen ─ Accept ─▶ Transfer strip/sheet
                Home ─ Receive ─▶ Receive screen (QR + Wi‑Fi Direct/hotspot hosting) for senders
                not on this Wi‑Fi
PAIR VIA QR     Sender: Choose devices ─ Scan a code ─▶ scanner ─▶ device added as port ─ send
                TV (no camera): Home ─ Show my QR; phone: Receive ─ Scan sender's QR
BROWSER         Home ─ Share with any device ─▶ step 1 Wi‑Fi QR, step 2 URL QR, live counters
HISTORY         Home ─ ⟲ (header) or Recent "All" ─▶ History ─ row ─▶ open / share / send again / install
```

### 3.3 Where the new features live

| Feature | Home |
|---|---|
| Send text / link / clipboard | Picker **Text** tab (phone only; hidden on TV, where a D-pad keyboard is painful [S6]). Shared text/URLs from other apps go straight to Choose devices. Received text goes into History with Copy and Open. |
| 4-digit confirmation code | Offer sheet (receiver) and the waiting state of the transfer sheet (sender), as a code readout. Hidden when the offer is trusted or auto-accepted. |
| "Always accept from…" + trusted badge | Offer sheet checkbox. A **Trusted** tag on the offer sheet and on ports. Settings → Receiving → Trusted devices list. |
| Auto-accept modes | Settings → Receiving → "Accept files from" (radio, 3 options). Summarised in the Home status line. |
| Resume | Transfer sheet: "Resuming at 63 %", with a 2 dp mark on the panel's meter at the resume point. Failed state (red panel): "Retry continues from 63 %". |
| CRC verify | Settings → Transfers → "Verify data". On the transfer sheet a `CRC` tag shows next to the speed unit while it's on. |
| Several devices | Choose devices → "Select" mode. The transfer sheet shows a per-device list and the summary "Sent to 2 of 3 devices". |
| History | Its own screen. Home shows the last 3. |
| Quick Settings tile | "Receive" toggle (§4.11). |
| Widget | Ports + Send (§4.12). |

---

## 4. Phone screens

Conventions in wireframes: `──` = 1 px `LINE` rule, `[ X ]` = secondary (outlined, neutral)
button, `[██ X ██]` = filled button, with its colour named in the note (ORANGE = `C_SEND`,
ULTRA = `C_RECEIVE`, YELLOW = `C_WEB`, PINK = `C_TEXT`, GREEN = `C_OK`, RED = `C_DANGER`),
`[▭] [▯] [▱]` = colour plate with device glyph (TV yellow, phone ultramarine, laptop green),
`▓▓▓` rows = a whole row filled with colour, `●` = LED, `01` = port number in `T_MONO INK_2`.
Width shown = 360 dp.

### 4.1 Home

```
┌────────────────────────────────────┐
│ FastDrop                     ⟲   ⚙ │  header 56: T_TITLE + iconButtons (History, Settings)
│                                    │
│ Himanshu's S21 FE                  │  T_HERO, tap → Settings › Device name
│ ● Home Wi‑Fi · 10.151.55.247       │  T_META; LED GREEN (ready); IP in T_MONO
│   Trusted devices send without     │  T_META INK_2 (accept-mode summary)
│   asking                           │
│────────────────────────────────────│
│ Nearby                     ⌗ Scan  │  T_SECTION + quiet button (camera phones only)
│ 01 [▭] Living room TV       Wi‑Fi ›│  port row 64: number, YELLOW plate, T_ROW, tag
│        TV · 10.151.55.20           │  T_META
│ 02 [▯] Pixel 8     Trusted  Wi‑Fi ›│  ULTRA plate
│        Phone · 10.151.55.31        │
│────────────────────────────────────│
│ Other ways                         │
│    ⌗   Scan a code               ›│  row 56, bare icon INK_2
│   [◎]  Share with any device     ›│  YELLOW plate (web job)
│        Opens in their browser…     │
│────────────────────────────────────│
│ Recent                         All │
│ [▪] IMG_2034.jpg      3.2 MB · 2m  │  real thumbnail 40 square; no image → category plate
│ [▪] FastDrop 2.1     [ Install ]   │  app icon; outlined small button
│ [¶] maps.app.goo.gl/…  [ Copy ]    │  PINK plate (text)
├────────────────────────────────────┤
│▓▓ Sending to Pixel 8 · 63 % ▓▓▓▓▓▓▓│  transfer strip 56: ORANGE fill (ULTRA when receiving)
├────────────────────────────────────┤
│ [██ Receive ██]  [████ Send █████] │  dock on SURFACE, 1px LINE top; 1:2 width
│   ULTRA, white text   ORANGE, ink  │
└────────────────────────────────────┘
```

Components: `Ui.header`, `Ui.statusLine` (new), `Ui.section`, `PortRow` (new), `Ui.row`,
`TransferStrip` (new), `Dock` (new). Port number = position in the sorted list (LAN → Wi‑Fi Direct
→ QR, then by name). It's stable while the screen is shown.

Port row anatomy (phone): `[01 · 12dp gap · colour plate 40 · 16 gap · title/meta column · tags · chevron 16]`.
Tags: `Wi‑Fi` / `Direct` / `QR` (link type) and `Trusted` (when we hold a trust key for that
receiver). Tag = `T_MONO`, `INK_2`, 1 px `LINE_STRONG`, 6 dp radius, 4×8 padding. Tags stay
neutral: the colour lives in the plate.

Port row states (phone):
- Rest: neutral row, coloured plate.
- **Pressed:** the whole row fills `C_SEND` orange while the finger is down (no ripple). Text,
  tags and chevron turn `#161513`, and the plate inverts (§2.7). On release, the picker or send
  starts. That makes "touching a device = sending to it" visible.
- **Selected** (multi-select): the same orange fill, kept.
- Disabled: 40% alpha, plate included.

Screen states:
- **Searching** (online, no devices): the Nearby list is replaced by a single 64 dp line with
  scan ticks, "Looking on Home Wi‑Fi…" `T_ROW`, and "Open FastDrop on the other device."
  `T_META`.
- **Wi‑Fi off:** a hollow LED, "Wi‑Fi is off", meta "Turn it on, or use Receive to connect
  directly.", and a quiet button "Wi‑Fi settings".
- **Not connected:** "Not on a Wi‑Fi network", meta "Join the same Wi‑Fi as the other device."
- **Server error** (`Server.error`): a RED LED, and the status line reads "Can't receive: *reason*"
  in the `C_DANGER` text tone with a "!" glyph.
- **Busy:** the transfer strip shows. Tapping a port while busy queues nothing and shows the
  snackbar "Wait for the current transfer to finish".
- **Recent empty:** "Nothing yet. Received files go to Download/FastDrop."

### 4.2 Picker (choose what to send)

```
┌────────────────────────────────────┐
│ ←  Choose                Select all│  header; quiet button
│ ▓Photos▓ Videos  Apps  Music  Files  Text │  tabs 40 tall; selected tab = solid block in its
│                                    │  category colour (Photos YELLOW, Videos ULTRA, Apps GREEN,
│────────────────────────────────────│  Music PINK, Files INK, Text PINK); others INK_2 text
│ ┌──┬──┬──┐                         │  3-col grid (TV 6), 2dp gaps, 0 radius, square crop
│ │  │▣ │  │   ▣ = selected: 4dp ORANGE frame inside the cell + 22dp ORANGE square
│ ├──┼──┼──┤      top-right with an ink check (the selection is what will be SENT)
│ │  │  │▣ │                         │
│ └──┴──┴──┘                         │
├────────────────────────────────────┤
│ 12 selected · 48 MB  [███ Next ███]│  dock; count T_ROW tnum; Next ORANGE
└────────────────────────────────────┘
```

- The picker opens on the **last-used tab** (default Photos). Delete the gradient `TabBar`
  indicator. The selected tab is a solid 6 dp-radius block in the category colour with `ON_*`
  text. It moves instantly; there's no sliding pill.
- Apps tab: list rows with the real app icon (40 dp), name, version · size. Checkbox square on
  the right; checked = `C_SEND` fill + ink check. The whole row fills orange while selected, as
  with ports.
- Files tab: rows "Pick files", "Pick a folder", "Browse storage" (bare icons), then "Picked
  files" section with neutral `SURFACE_2` plates.
- **Text tab (new):** a multiline field on `SURFACE_2` (min 5 lines, `T_BODY`) with a 4 dp
  `C_TEXT` pink block on its leading edge (the job colour, as a solid strip, not a tint), and a
  quiet button **Paste** (fills from clipboard; disabled when the clipboard is empty). Meta
  counter "128 / 100 000" in `T_MONO`. Next (orange) is enabled when the text isn't empty. Text
  and files can't be mixed: switching to Text while files are selected shows "Text is sent on
  its own. Clear 12 selected files?" with [Keep files] [██ Clear ██ RED].
- Permission states use one pattern: an `INK_2` icon 32 dp, `T_ROW` "Allow access to your
  photos", `T_META` reason (one sentence), and an outlined button "Allow access".
- "Showing only the photos you chose" banner: a full-bleed `SURFACE_2` row with an `INK` "!"
  glyph and "Allow all". Neutral, because it isn't one of the six jobs.
- Loading: centred scan ticks and "Reading your apps…".

### 4.3 Choose devices (Send screen)

```
┌────────────────────────────────────┐
│ ←  Send to                  Select │  header; "Select" toggles multi-select
│ 12 items · 48 MB             Edit  │  selection line T_ROW + quiet button
│ 8 photos · 3 videos · 1 app        │  T_META
│────────────────────────────────────│
│ On this Wi‑Fi                      │  T_SECTION
│ 01 [▭] Living room TV       Wi‑Fi ›│  YELLOW plate
│▓02 [▯] Pixel 8     Trusted  Wi‑Fi ✓▓│  selected in multi-select: whole row ORANGE
│ Nearby · Wi‑Fi Direct              │
│ 03 [▯] Galaxy A15          Direct ›│
│        Connects directly when you send
│────────────────────────────────────│
│ Can't see it?                      │
│    ⌗  Scan a code                ›│
│    ⊞  Show my QR                 ›│  expands QR panel inline (white plate 240)
│    ↻  Search this Wi‑Fi           │  scan ticks while running
│    #  Enter IP address           ›│
│   [◎] Share with any device      ›│  YELLOW plate
├────────────────────────────────────┤
│ [████ Send to 2 devices ████████]  │  dock (multi-select only), ORANGE
└────────────────────────────────────┘
```

- **Delete the radar.** The list is the radar.
- Tapping a port sends immediately (fast path): the row flashes orange on press (§4.1). Haptic
  `VIRTUAL_KEY`.
- **Multi-select:** "Select" turns the chevrons into 22 dp checkbox squares (`LINE_STRONG`
  outline). Checking a port fills **the whole row** `C_SEND` with ink content and an ink ✓
  (`CLOCK_TICK`). The header changes to "2 selected" with "Cancel". A dock appears with the
  orange "Send to 2 devices". A long-press on a port also enters this mode with that port checked.
- Text send: the selection line becomes a `SURFACE_2` well with a 4 dp pink leading block,
  showing the first 3 lines of the text (`T_BODY`), plus "Edit". Ports whose HELLO says `v < 2`
  render disabled with the meta "Needs FastDrop 2.1 or newer to receive text".
- Banners (Location off, Nearby devices permission): a full-bleed `SURFACE_2` row with an `INK`
  "!" glyph, `T_ROW` title, `T_META` reason, chevron. Never a card.
- Empty: the same scan-tick line as Home. "Can't see it?" moves up directly under it.

### 4.4 Receive (be visible beyond this Wi‑Fi)

```
┌────────────────────────────────────┐
│ ←  Receive                         │
│▓▓ Visible as Himanshu's S21 FE ▓▓▓▓│  status block 72: full-bleed ULTRA fill, white T_ROW
│▓▓ Wi‑Fi Direct · ready ▓▓▓▓▓▓▓▓▓▓▓▓│  white T_META (hostStatus)
│────────────────────────────────────│
│  FastDrop app │ Other devices      │  segmented: 1px outline, selected = INK fill
│  ┌──────────────────────────┐      │
│  │      QR on white plate    │      │  240 dp, 16 dp quiet zone, 0 radius
│  └──────────────────────────┘      │
│  Scan this from FastDrop on the    │  T_BODY centred, ≤ 2 lines
│  other device.                     │
│────────────────────────────────────│
│ Network     DIRECT‑xy‑S21    ⧉     │  spec rows: label T_META, value T_MONO, copy icon
│ Password    ••••••••      👁  ⧉     │  (eye icon = ic_eye)
│ Address     192.168.49.1     ⧉     │
│────────────────────────────────────│
│ On the same Wi‑Fi? Nearby phones   │  T_META INK_2, no icon, no card
│ already see you.                   │
├────────────────────────────────────┤
│ [██████ Scan sender's QR ███████]  │  dock (camera phones), ULTRA, white text
└────────────────────────────────────┘
```

- Delete the radar and the avatar hero. The ultramarine status block is the hero: you can see
  from across the room that this phone is in receive mode.
- Delete the Auto-accept toggle from this screen. It now lives in Settings as a mode.
- States: **starting**: neutral block (`SURFACE_2`, `INK` text) with scan ticks and "Setting
  up Wi‑Fi Direct…". **Ready**: ultramarine block. **Failed**: red block, white text "Couldn't
  start Wi‑Fi Direct" plus meta "Same Wi‑Fi still works." **Permission needed**: a neutral
  `SURFACE_2` banner row above the QR, and the QR is replaced by the permission prompt.

### 4.5 Incoming offer (sheet)

Bottom sheet on `SURFACE`, 12 dp top corners. Its top half is a **full-bleed ultramarine panel**
(`C_RECEIVE`, white content), so an incoming offer is recognisable before it's read. The
countdown is a **3 dp white bar along the panel's top edge** that shrinks right→left in
**1-second steps** (no tween, so it's cheap and needs no animation).

```
┌────────────────────────────────────┐
│▓━━━━━━━━━━━━━━━━━━━━━━━━━━━━━   ▓▓▓│  countdown bar (white on ULTRA)
│▓ [▯] Pixel 8             Trusted ▓▓│  plate inverted (white plate, ULTRA glyph); tag white outline
│▓ Pixel 8 wants to send 3 photos ▓▓▓│  T_TITLE white (≤ 2 lines)
│▓ and 1 video · 48 MB ▓▓▓▓▓▓▓▓▓▓▓▓▓▓│
│▓ Code ●●●  ●●●  ●●●  ●●●  ▓▓▓▓▓▓▓▓▓│  code readout: white dots on ULTRA, unlit #4F5AFF
│▓ Check that Pixel 8 shows the same ▓│  T_META white
│▓ code. ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓│
│────────────────────────────────────│  (neutral SURFACE below)
│ IMG_2034.jpg              3.2 MB   │  first 4 names, T_META, sizes in T_MONO, "and 8 more"
│ VID_0001.mp4               41 MB   │
│ ☐ Always accept from Pixel 8       │  checkbox row 56; checked = ULTRA fill + white check
│ [  Decline  ] [███ Accept ███████] │  1:1 widths; Accept ULTRA, white text
│ Closes in 54 s                     │  T_META tnum, centred
└────────────────────────────────────┘
```

- **Trusted offer under "Ask every time":** show the `Trusted` tag, hide the code (the sender
  doesn't show one either), and hide the checkbox.
- **Untrusted:** show the code and the checkbox. Code text equivalent for TalkBack: "Code 0 4 2 7".
- **Text offer:** the panel is **pink** (`C_TEXT`, ink content) instead of ultramarine, because the
  content type is the news. The title reads "Pixel 8 wants to send a link" (when the whole text is
  one `http(s)` URL) or "…wants to send text". The file list is replaced by a `SURFACE_2` well
  showing the text, max 6 lines, `T_BODY`, with URLs underlined. Accept stays ultramarine (the
  receive job). After Accept the sheet changes in place to show the full text (scrollable, max 60%
  height) with [ Copy ] [██ Open link ██ PINK] (or [██ Copy ██ PINK] alone when it isn't a URL)
  and the quiet button "Done".
- **Auto-accepted** (trusted, or Everyone): no sheet. Files show the transfer strip in
  ultramarine. Text shows a snackbar "Pixel 8 sent a link" with action "Open" (or "Copy"), plus
  a notification.
- **Declined or timed out:** the sheet closes. No snackbar is needed on the receiver.

### 4.6 Transfer (sheet; full height on phone)

Opens for outgoing transfers. Incoming auto-accepted transfers use the strip, and tapping the
strip opens this sheet. The top of the sheet is **the panel**: a full-bleed solid block whose
colour is the state (§2.9). Everything on it uses the panel's `ON_*` colour.

```
RUNNING (sending)                     WAITING FOR ACCEPT (sender)
┌────────────────────────────────────┐┌────────────────────────────────────┐
│▓ Sending to Living room TV   Hide ▓││▓ Waiting for Pixel 8 to accept  ▓▓│  ORANGE panel
│▓                                  ▓││▓ Code ●●●  ●●●  ●●●  ●●●  ▓▓▓▓▓▓▓▓│  ink dots
│▓  ●●● ●●●  ● ●●●     MB/s        ▓││▓ Check that Pixel 8 shows the    ▓│
│▓  (dot matrix "46.2", ink)  CRC   ▓││▓ same code.  ↻ scan ticks (ink)  ▓│
│▓  ▮▮▮▮▮▮▮▮▮▮▮▮▮▮▯▯▯▯▯▯▯▯▯▯   63 %  ▓││────────────────────────────────────│
│▓  1.2 GB of 1.9 GB · 0:14 left    ▓││ [           Cancel           ]     │
│▓  Resumed at 41 %                 ▓│└────────────────────────────────────┘
│────────────────────────────────────│  (neutral SURFACE below)
│ VID_0001.mp4          820 / 1400 MB│  per-file rows, sizes T_MONO
│ IMG_2034.jpg               3.2 MB ✓│  ✓ in GREEN
│────────────────────────────────────│
│ [            Cancel             ]  │
└────────────────────────────────────┘
Panel colour: ORANGE sending · ULTRA receiving · GREEN done · RED failed
```

- **Meter** (on the panel): 24 segments (TV 32), 8 dp tall, 2 dp gaps, 0 radius. Filled =
  the panel's `ON_*` colour. Empty = the panel's unlit-dot shade (§2.3). On resume, the
  segments that were already there are filled like the rest, and a 2 dp gap in the `ON_*`
  colour marks the resume point. Percent in `T_FIGURE`.
- **Readout:** see §2.9. The `CRC` tag next to the unit shows when verify is on.
- **Multi-device** (runs are sequential): the panel shows the **current** device. Above the file
  rows sits a "Devices" section, one row per target with its colour plate: `01 [▭] Living room TV
  — Sent ✓` (green ✓), `02 [▯] Pixel 8 — Sending 41 %`, `03 [▱] MacBook — Waiting`. The title
  reads "Sending to 3 devices". The panel stays orange until the last device finishes, then turns
  green (all sent) or red (any failed).
- **Done:** the panel turns **green** and shows the average speed. Title "Sent to Living room TV"
  (or "Saved 12 files to Download/FastDrop" when receiving), meta "1.9 GB in 0:41". Buttons
  below the panel: received single APK → [██ Install ██ GREEN] [ Done ]; received single file →
  [██ Open ██ GREEN] [ Done ]; else [ Done ] alone (outlined; the green panel already says it).
  Multi: "Sent to 2 of 3 devices" on a **red** panel if any failed, with failed device rows
  showing "!" in `C_DANGER` text tone and an inline "Retry". Haptic `CONFIRM`. **No confetti.**
  The green block *is* the celebration.
- **Failed:** the panel turns **red** with the frozen last speed. Title "Couldn't finish sending
  to Pixel 8" with a "!" glyph (white). The cause in white `T_BODY` on the panel ("Connection
  lost.", "Pixel 8 declined.", "Data check failed.", "Pixel 8 needs FastDrop 2.1 or newer to
  receive text."), and the remedy under it ("Retry continues from 63 %." only when resume
  applies). Buttons below: [ Close ] [██ Retry ██ ORANGE] (Retry is a send). Incoming failures
  have no Retry, because the sender retries. Haptic `REJECT`.
- **Cancelled:** a neutral panel (`SURFACE_2`, `INK` dots and text). Title "Cancelled", meta
  "1.2 GB of 1.9 GB sent", button [ Done ].
- **Slow-link tip:** a neutral `SURFACE_2` row under the panel: "Slow 2.4 GHz Wi‑Fi. Switch both
  devices to 5 GHz for 3–5× the speed."
- **Transfer strip** (Home and the other screens' docks): 56 dp, **solid fill in the transfer's
  colour** (orange out, ultramarine in), `ON_*` text: `T_ROW` "Sending to Pixel 8", `T_META`
  "63 % · 0:14 left" (tnum). A 4 dp meter across the top edge uses the `ON_*` colour on the
  unlit shade. Tap opens the sheet. When it finishes while hidden, the strip turns green or red
  for 4 s, then collapses.

### 4.7 History (new screen)

```
┌────────────────────────────────────┐
│ ←  History                     ⋮   │  overflow: "Clear history"
│ ▓All▓ Received  Sent  Text         │  tabs: selected = solid block (All INK, Received ULTRA,
│                                    │  Sent ORANGE, Text PINK), ON_* text
│────────────────────────────────────│
│ Today                              │  T_SECTION
│ [▪] IMG_2034.jpg             14:02 │  real thumb 40 (else category plate); time T_META tnum
│   3.2 MB · from Pixel 8            │
│ [▪] FastDrop 2.1       [ Install ] │  APK: app icon; small outlined button 36 tall
│   v2.1 · 310 KB · from Pixel 8     │
│ [¶] maps.app.goo.gl/…    [ Copy  ] │  text: PINK plate with ¶, first line
│   Link · from MacBook              │
│ [↑] 12 items                 13:40 │  sent batch: ORANGE plate ↑ (received batch: ULTRA ↓)
│   48 MB · to Living room TV · avg 46.2 MB/s
│ Yesterday                          │
│ …                                  │
└────────────────────────────────────┘
```

- Tap: open the file, open a received APK group (installs it), show text in a sheet with
  Copy/Open, or expand a sent batch to show its files.
- Long-press (TV: OK opens the action sheet) gives an action sheet: **Open**, **Share**
  (Android share), **Send again** (puts the items in the selection → Choose devices), **Install**
  (APKs), **Show in folder**, **Remove from history** (file stays on disk).
- Missing file: the row's meta shows "File was moved or deleted" in `INK_3`, and Open is
  disabled.
- Empty: "Nothing sent or received yet." Per-tab empty: "No received files yet." and so on.
- Grouping by day: "Today", "Yesterday", then "12 Nov".
- Failed transfers appear as rows with a RED plate "!" and meta "Didn't finish · 63 %"; tap →
  Retry (orange) for sends.

### 4.8 Settings

```
┌────────────────────────────────────┐
│ ←  Settings                        │
│ Device                             │
│   Name            Himanshu's S21 FE›│
│ Receiving                          │
│   Accept files from                │  T_ROW
│   ◉ Trusted devices                │  radio rows 56, ◉ = INK dot inside LINE_STRONG ring
│     Devices you trust send without │
│     asking. Others ask.            │
│   ○ Ask every time                 │
│   ○ Everyone                       │
│     Anyone on your network can send│
│     without asking.                │
│   Trusted devices              2  ›│  → sub-screen
│   Save to       Download/FastDrop  │  T_MONO value (read-only)
│ Transfers                          │
│   Parallel streams   [−]  4  [+]   │  stepper 1–8, value T_FIGURE-small tnum
│   Verify data                 [◻ ] │  toggle; meta "Checks every chunk (CRC32). Slower on TVs and old phones."
│   Wi‑Fi Direct on 5 GHz       [■ ] │
│ Appearance (phone only)            │
│   Theme   System default › (Light/Dark)
│ Apps                               │
│   Show system apps            [◻ ] │
│   Share FastDrop app              ›│
│ Permissions                        │
│   Camera                   Allowed │  tag; "Allow" = quiet button
│ About                              │
│   Version 2.1 · Wi‑Fi Direct supported
└────────────────────────────────────┘
```

- Sections = `Ui.section` (rule + `T_SECTION` label). There are **no cards**.
- **Trusted devices sub-screen:** rows `▯ Pixel 8` / meta "Added 3 Nov" / secondary button
  "Remove". Remove asks for confirmation in a sheet: "Stop trusting Pixel 8? It will have to ask
  again next time." [ Cancel ] [██ Remove ██ RED, white text]. Empty: "No trusted devices. Tick "Always accept"
  when you accept a transfer."
- Toggle (restyled): track 40×22, 6 dp radius. Off: 1 px `LINE_STRONG` outline with a 16 dp
  `INK_3` knob. On: `INK` fill with a `BG` knob. Settings are neutral on purpose: none of the
  six jobs applies to a preference, and neutral settings keep the colour code meaningful.
- Trusted-device rows use their device-type colour plates.
- Stepper replaces the 8-option segmented control: two 40 dp outlined square buttons around a
  `tnum` value.

### 4.9 QR scan (full-screen camera)

```
┌────────────────────────────────────┐
│ ✕                      Scan a code │  on black, white text
│                                    │
│      ┏━━                ━━┓        │  4 corner brackets, 3dp white, 240 dp box
│                                    │  outside the box: 50% black dim
│      ┗━━                ━━┛        │
│ Point at the code on the other     │  T_BODY white, centred
│ device.                            │
│                                    │
│ [ Torch ]   [ From photo ]         │  outlined white buttons on black, 6 dp
└────────────────────────────────────┘
```

- No moving scan line. On success the brackets turn `C_OK` green and the hint becomes a green
  block with ink text, haptic `CONFIRM`, the text
  changes to "Found Living room TV", and the screen closes after 400 ms.
- Not a FastDrop code: the text changes to "That's not a FastDrop code" on a `C_DANGER` red block
  with a white "!" glyph for 2 s.
- No camera permission: a centred `T_ROW` "Allow camera to scan", meta, [ Allow camera ] (outlined white), and
  "From photo" still works.

### 4.10 Share with any device (WebShare)

```
┌────────────────────────────────────┐
│ ←  Share with any device    Stop   │  "Stop" = quiet button, C_DANGER text tone
│▓▓ Sharing 12 files + FastDrop app ▓│  status block 72: full-bleed YELLOW, ink text
│▓▓ No app needed. Stays offline. ▓▓▓│
│────────────────────────────────────│
│ 1  Join this Wi‑Fi                 │  step number in a 24 dp YELLOW square, ink T_MONO
│    [ QR plate 200 ]                │
│    Network  DIRECT‑xy‑S21  ⧉       │
│    Password k3f9…          ⧉       │
│────────────────────────────────────│
│ 2  Open in the browser             │
│    [ QR plate 200 ]                │
│    http://192.168.49.1:8080  ⧉     │  T_MONO
│────────────────────────────────────│
│ Live                               │
│ ┌────────┬────────┬────────┬───────┐ │  spec grid: 1px LINE cells, value T_FIGURE-small
│ │ 3      │ 1      │ 120 MB │ 0 B   │ │  tnum INK, label T_META below; a cell flashes
│ │        │        │        │       │ │  YELLOW fill for 600 ms when its value changes
│ │Downloads│Uploads│ Sent   │Received│ │
│ └────────┴────────┴────────┴───────┘ │
│ 14:02  iPhone downloaded IMG_2034  │  event rows T_META
└────────────────────────────────────┘
```

Error state: the status block turns `C_DANGER` red with white text "Couldn't start sharing",
meta "Port 8080 is busy. Close other sharing apps and try again.", and below it
[██ Try again ██ YELLOW]. Starting: neutral `SURFACE_2` block with scan ticks.

### 4.11 Quick Settings tile

Per Google's guidance (toggle, frequent use, ≤ 2 tiles, don't use it just to launch the app [S14]):

- **One tile, label "FastDrop"** (≤ 18 chars). It's a **toggle for "Receive"**: when active,
  FastDrop keeps receiving in the background through `TransferService` and is visible over Wi‑Fi
  Direct/hotspot, for **10 minutes** (the same pattern as Quick Share's "Everyone for 10
  minutes" [S4]).
- States: `STATE_INACTIVE` subtitle "Off". `STATE_ACTIVE` subtitle "Visible · 9 min" (updated
  each minute while listening). `STATE_UNAVAILABLE` subtitle "No Wi‑Fi" only when neither Wi‑Fi
  nor Wi‑Fi Direct is available.
- Icon: a new 24 dp solid-white vector `ic_tile` (a square port with a dot in its centre). The
  system tints it.
- `TOGGLEABLE_TILE` = true, active mode, category `CATEGORY_CONNECTIVITY`. Long-press
  (`ACTION_QS_TILE_PREFERENCES`) opens the Receive screen.
- An incoming offer while the tile is active shows a high-priority notification with Accept and
  Decline actions. Opening it shows the offer sheet with the code. Untrusted offers never accept
  from the notification alone if the code is required: the notification's action is "Review".
- Offer to add the tile (`requestAddTileService`, API 33+) once, after the user's first receive.

### 4.12 Home-screen widget

`RemoteViews` can't host code-built views, so the widget is **the one justified place for XML
layouts** (`res/layout/widget_*.xml`). Put the colours in `res/values/` and
`res/values-night/`, and use the same hexes as the tokens.

```
4×1                                      2×1
┌──────────────────────────────────────┐ ┌─────────────────┐
│ [▭]TV  [▯]Pixel 8 [▱]Mac  [██Send██] │ │▓▓ Receiving 9m ▓│  ULTRA block (tap toggles)
└──────────────────────────────────────┘ │ [████ Send ████]│  ORANGE
  plates in device colours                └─────────────────┘
```

- Background `SURFACE`, radius = `system_app_widget_background_radius` on API 31+ (else 12 dp),
  no shadow, 1 px `LINE` stroke.
- Colours are the same six fills and plates as the app (the widget is a small instrument:
  colour keys on a neutral body). Receive off = neutral `SURFACE_2` block with `INK` text
  "Receive"; on = ultramarine block, white text.
- 4×1: up to 3 **recent recipients** from History as ports (colour plate + name `T_META`). Tapping one
  opens the picker targeted at that device. Send opens the picker. 2×1: Receive status (tap
  toggles, like the tile) + Send.
- No live progress in the widget. That belongs to the notification.

---

## 5. TV

### 5.1 Canvas and grid

- Design at **960×540 dp**. Safe margins: **48 dp** left/right and **27 dp** top/bottom
  [S5b]. Backgrounds go edge to edge, and nothing focusable sits outside the margins.
- Content width 864 dp. **Two panes:** left **300 dp**, gap **32 dp**, right **532 dp**. This
  is the "task-forward" two-pane layout [S5b]. Panes are separated by a 1 px `LINE` vertical rule
  at the gap's centre.
- Overlays (offer, transfer, action sheets): a **centre overlay 600 dp wide**, `SURFACE`, 12 dp
  radius, `SCRIM` behind. Never a bottom sheet on TV.
- Dark theme only (§2.2). Backgrounds stay off-black. At rest, colour appears only as plates
  and 6 dp "key" blocks (below). Full colour fills appear only on **the focused element**, the
  transfer panel and the offer panel. That keeps the colour code visible from the sofa without
  big warm areas glowing in a dark room [S7].

### 5.2 Type on TV

Use the TV column in §2.4: row titles 18 sp, body 16 sp, meta 14 sp, nothing smaller. The
readout pitch is 12 dp.

### 5.3 Focus (exact)

**Colour fill + scale**, combining the colour and scale indications from Google's focus system
[S5c][S6]:

- **Every focusable has a focus colour**, which is its job colour from §2.3:

  | Element | Focus fill | Content on fill |
  |---|---|---|
  | Port rows (normal mode); "Send files"; "Send to N devices"; Next; Retry | `C_SEND #FF5A1F` | `#161513` |
  | "Show my QR"; Accept; "Scan sender's QR" | `C_RECEIVE #2E3BFF` | `#FFFFFF` |
  | "Share with any device"; "Start sharing" | `C_WEB #FFC21A` | `#161513` |
  | Copy / Open link on a received text | `C_TEXT #FF5FA2` | `#161513` |
  | Install / Open after a finished receive | `C_OK #1FAE52` | `#161513` |
  | Remove trusted device; Stop sharing | `C_DANGER #D7192F` | `#FFFFFF` |
  | Everything else (settings, history rows, tabs, Decline, Cancel, Hide, ports in multi-select mode) | `INK #ECEAE4` | `#0F0F0E` |

- **At rest on TV**, a coloured element is neutral (`SURFACE_2` fill for buttons, transparent
  for rows) with a **6×24 dp key block** in its focus colour at the leading edge, like the
  coloured keys on an OP-1. Ports show their device plate instead. So the code is readable
  before focusing, and focus visibly "lights the key up" to a full fill.
- **Scale:** rows **1.03**, buttons and plates **1.06**, picker grid thumbnails **1.08**
  (thumbnails also get a 4 dp `C_SEND` frame inside the cell, because a fill can't show on an
  image). Leave 4 dp between list rows and 8 dp between grid items so scaled items never overlap
  [S5b]. Scale pivots at the centre.
- Timing: the fill swaps **instantly** ("instant and distinct feedback" [S5]). The scale takes
  100 ms with `EASE_ENTER` when animations are on, and is instant when they're off.
- Inside a filled element every child uses the fill's content colour. Plates invert (§2.7).
- Pressed (OK held): the fill darkens to that colour's unlit-dot shade (§2.3; for `INK`, use
  `INK_2`), with no scale change.
- Multi-select: selected = orange fill kept at rest, plus ✓. Focus in this mode uses the `INK`
  fill, so in this mode orange means "selected". Focused and selected = orange plus scale plus ✓.
- Disabled: 40% alpha and **not focusable**.
- Delete `FocusRing`, the violet halo and the `translationZ` lift.

Implementation: each focusable gets a `StateListDrawable` (`state_pressed` → shade,
`state_focused` → focus colour, default → rest drawable with the key block as a `LayerDrawable`
inset). Children use `ColorStateList`s for text and `imageTintList` (API 21+) with
`isDuplicateParentStateEnabled = true`, so they flip with the parent. Scale is set in the existing
`OnFocusChangeListener`.

### 5.4 D-pad rules

- **Vertical moves within a pane, horizontal moves between panes** [S6]. Right from the left
  pane goes to the last-focused item in the right pane (or its first item). Left goes back to
  the left pane's last-focused item. Set `nextFocusLeftId`/`nextFocusRightId` explicitly, and
  don't trust geometric search across panes.
- Every action is reachable by D-pad. No action is long-press-only: anything behind long-press
  on phone is behind OK → action overlay on TV.
- **Number keys 1–9** (remote or keyboard) send to port 01–09 on Home and on Choose devices.
  That's why ports are numbered. Show the hint line "Press 1–9 to send to a device" (`T_META`)
  under the Nearby section label when the device has number keys (`KeyCharacterMap`).
- Back closes overlays first, then pops the screen. On Home, Back exits (no confirm).
- When a list changes (a device appears), focus stays on the same item (keyed rows, as
  `SendScreen.reconcile` already does).
- Initial focus: Home → port 01 if any, else "Send files". Overlays → their primary button.

### 5.5 Screens on TV

**Home**
```
┌──────────────────────────────────────────────────────────────────────┐
│  48dp margin                                                          │
│  Himanshu's TV                       │ Nearby      Press 1–9 to send  │
│  ● Home Wi‑Fi · 192.168.1.40         │ 01 [▯]Pixel 8     Trusted Wi‑Fi│   ULTRA plate
│  Trusted devices send without asking │ 02 [▱]MacBook            Wi‑Fi │   GREEN plate
│ ──────────────────────────────────── │────────────────────────────────│
│ ▌Send files                     ▶    │ Recent                         │   ▌ = 6×24 dp key block:
│ ▌Show my QR                          │ [▪]IMG_2034.jpg    3.2 MB · 2m │   ORANGE, ULTRA, YELLOW
│ ▌Share with any device               │ [▪]FastDrop 2.1        Install │
│  History                             │                                │
│  Settings                            │                                │
└──────────────────────────────────────────────────────────────────────┘
```
Left pane = an action list (64 dp rows, no icons, `T_ROW`, key blocks for the three coloured
jobs). There is no dock on TV. Focused "Send files" = a full orange row at 1.03. A focused port =
a full orange row (OK sends to it). The transfer strip (solid orange or ultramarine) appears at
the top of the right pane while a transfer runs. Nothing scrolls sideways.

**Show my QR (TV-only entry; this is the TV's Receive screen)**: the left pane is topped by the
ultramarine status block "Visible as Himanshu's TV" (white text), and numbered steps in `T_BODY`: "1 On your phone, open FastDrop →
Receive", "2 Tap Scan sender's QR", "3 Point it at this screen". Right pane: a QR plate at
**300 dp**, with network/password/address spec rows under it. Initial focus is the "FastDrop
app / Other devices" segmented control.

**Send files (picker + file browser)**: the tabs are a left rail of text items (Apps, Photos,
Videos, Music, Files, Browse storage), each with a key block in its category colour. The
selected tab is a full fill in that colour. The content grid is 6 columns in the right area.
"Browse storage" (`BrowserActivity`, **TV-only** as a top-level tab; on phone it stays inside
Files) uses a breadcrumb row and 64 dp rows. The bottom-right dock holds "12 selected · 48 MB"
and [Next].

**Choose devices**: right pane = ports, left pane = selection summary + "Select several" +
"Search this Wi‑Fi" + "Enter IP address" + "Show my QR". Multi-select: OK toggles a checked
port, and the left pane's "Send to 3 devices" becomes the primary item.

**Incoming offer**: centre overlay 600 dp. Its top is the ultramarine panel (pink for text),
the same as the phone. The code readout uses pitch 10 dp. Buttons row: [Decline] [Accept], **initial focus Accept**. Up from the buttons
focuses the "Always accept" checkbox. The countdown bar sits along the overlay's top edge.

**Transfer**: centre overlay 600 dp. Its top 60% is the coloured panel (orange, ultramarine,
green or red), the room-visible signature. Readout pitch 12 dp, meter 32 segments, up to 4 file
rows below the panel.
[Hide] [Cancel]. Done → [Open]/[Install]/[Done], with initial focus on the first.

**History**: a left rail filter (All, Received, Sent, Text) and the list on the right. OK on a row
opens the action overlay (Open, Install, Send again, Remove).

**Settings**: a single centred column 600 dp wide. The same sections as phone, minus
Appearance. The trusted-devices list opens in the same column.

**Share with any device**: left pane = status, the live spec grid and Stop. Right pane = the two
QR plates side by side (220 dp each) with their steps above.

**QR scan**: not offered on TVs without a camera (`QrScanActivity.hasCamera`). Where a camera
exists, same as phone.

---

## 6. Component kit changes

### 6.1 Theme.kt: token migration

`Theme` becomes theme-aware: colour fields change from `const val` to `var`, set by
`Theme.resolve(ctx)`. Call it in `MainActivity.onCreate`, in every other Activity's
`onCreate`, and in `onConfigurationChanged` **before** the screen rebuild (the manifest already
lists `uiMode` in `configChanges`, so the rebuild path exists). Add a `Theme.dark: Boolean`.

| Old | New | Light | Dark |
|---|---|---|---|
| `BG #07080D` | `BG` | `#F3F1EC` | `#0F0F0E` |
| `BG_2 #0B0D15` | **delete** (use `BG`) | | |
| `SURFACE #11131C` | `SURFACE` | `#FBFAF7` | `#191917` |
| `SURFACE_2 #181B27` | `SURFACE_2` | `#E7E4DD` | `#232321` |
| `SURFACE_3 #222636` | **delete** (thumb placeholder → `SURFACE_2`) | | |
| `STROKE 12% white` | `LINE` | `#D9D5CC` | `#2B2A27` |
| `STROKE_STRONG 22% white` | `LINE_STRONG` | `#8C887F` | `#6F6C66` |
| `SCRIM 70% black` | `SCRIM` | `#99000000` | `#B3000000` |
| `TEXT #F3F5FA` | `INK` | `#161513` | `#ECEAE4` |
| `MUTED #8E95AB` | `INK_2` | `#5B5852` | `#A29F98` |
| `FAINT #8089A0` | `INK_3` | `#8C887F` | `#6F6C66` |
| `SEND_A #8B5CF6`/`SEND_B #3B82F6` (gradient), `ACCENT #7C6CFF` | `C_SEND` (fill, both themes) + `ON_SEND` | `#FF5A1F` / on `#161513` | same |
| `RECV_A #10D9A0`/`RECV_B #0EA5E9`, `ACCENT_2 #4FA3FF` | `C_RECEIVE` + `ON_RECEIVE` | `#2E3BFF` / on `#FFFFFF` | same |
| `WEB_A #F59E0B`/`WEB_B #EF4466` | `C_WEB` + `ON_WEB` | `#FFC21A` / on `#161513` | same |
| (new) | `C_TEXT` + `ON_TEXT` | `#FF5FA2` / on `#161513` | same |
| `SUCCESS #34D399` | `C_OK` + `ON_OK` | `#1FAE52` / on `#161513` | same |
| `DANGER #FF5C7A` | `C_DANGER` + `ON_DANGER` | `#D7192F` / on `#FFFFFF` | same |
| `WARNING #FFB547` | **delete** → neutral `SURFACE_2` banner with `INK` "!" | | |
| (new) coloured-text tones | `TXT_SEND`, `TXT_RECEIVE`, `TXT_OK`, `TXT_DANGER` | `#C53D0B`, `#2E3BFF`, `#13753A`, `#D7192F` | `#FF5A1F`, `#8E97FF`, `#1FAE52`, `#FF6B6B` |
| (new) unlit-dot / pressed shades | `SH_SEND`, `SH_RECEIVE`, `SH_OK`, `SH_DANGER` (+ `SH_WEB #DAA619`, `SH_TEXT #DA538B` for pressed fills) | `#DA4F1D`, `#4F5AFF`, `#1E9648`, `#DD3E50` | same |
| `CAT_APPS`, `CAT_PHOTOS`, `CAT_VIDEOS`, `CAT_MUSIC`, `CAT_FILES` | **delete** → `Theme.plateFor(kind)` returning a (fill, on) pair from the identity table in §2.3 | | |
| (new) | `Theme.plateFor(deviceType)` (phone → RECEIVE, tv → WEB, laptop → OK) | | |
| `BRAND`, `SEND`, `RECEIVE`, `WEB` arrays | **delete** (no gradients) | | |
| `RADIUS_CARD 24`, `RADIUS_BUTTON 18`, `RADIUS_SMALL 12` | `R_CTRL = 6f`, `R_SHEET = 12f` (0 is the default) | | |
| `GUTTER 20` | `GUTTER = 16` | | |
| `TV_GUTTER 48` | `TV_GUTTER = 48`, add `TV_SAFE_V = 27` | | |
| (new) | `S1..S7 = 4, 8, 12, 16, 24, 32, 48` | | |
| `DUR_FAST 160`, `DUR 260`, `DUR_SLOW 420` | `D_TAP = 90`, `D_STATE = 150`, `D_SHEET = 220`, `D_SHEET_OUT = 180` | | |
| `EASE_OUT (0.2,0,0,1)` | `EASE_ENTER (0,0,0,1)` | | |
| `EASE_IN_OUT (0.4,0,0.2,1)` | `EASE_MOVE (0.2,0,0,1)` | | |
| `EASE_IN (0.4,0,1,1)` | `EASE_EXIT (0.3,0,1,1)` | | |
| `SPRING` (Overshoot 1.3) | **delete** | | |
| `REGULAR`, `MEDIUM` | keep | | |
| `BOLD`, `BLACK` | **delete** → `MEDIUM` | | |
| (new) | `MONO = Typeface.MONOSPACE` | | |
| (new) | `fun rule(ctx)`: px thickness `max(1, (density/2).toInt())` | | |

`res/values/styles.xml`: `windowBackground` → `@color/bg`. Add `res/values-night/colors.xml`
with `bg = #0F0F0E` and make `res/values/colors.xml` `bg = #F3F1EC`. `colorAccent`/`colorPrimary`
→ `#FF5A1F` (system dialogs, EditText cursor). Status and nav bar icons go dark in light mode
(`View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR` on API 23+, `LIGHT_NAVIGATION_BAR` on API 26+, or
`WindowInsetsController.setSystemBarsAppearance` on API 30+). Below API 23, light mode gets a
`#99000000` status-bar scrim so the white icons stay readable.

Implement the six colours as a small `enum class Job(fill, on, shade, textLight, textDark)` with
`SEND, RECEIVE, WEB, TEXT, OK, DANGER`. Components take a `Job`, never a raw colour, which is how
the "one colour, one meaning" rule gets enforced in code. `Theme.alpha()` stays only for the
scrim. Calling it on a `Job` colour is a review blocker.

### 6.2 Ui.kt: keep, restyle, replace, delete

| Component | Action | Notes |
|---|---|---|
| `text()` | **Keep + extend** | Add params `lineHeightSp`, `tnum: Boolean`. Default line height from the style table. |
| `title()` | **Restyle** | `MEDIUM`, `T_TITLE`, no negative tracking. |
| `label()` | **Replace** → `section(ctx, label, trailing?)` | Full-bleed rule + sentence-case `T_SECTION` + optional trailing quiet button. No uppercase, no tracking. |
| `icon()` | Keep | Default tint `INK`. |
| `gradient()` | **Delete** | |
| `rounded`/`oval`/`ripple` | Keep | Ripple colour = `INK` at 12% alpha. Bounded. Not used on TV (state list instead). |
| `glass()`, `glow()`, `focusFill()`, `FocusRing` | **Delete** | Replaced by `focusFill(v, job?)` (§5.3). |
| `focusable()` | **Rewrite** as `focusFill(v, job: Job?, scale)` | Fill swaps instantly to the job colour (or `INK` when `job == null`) and scales 1.03 / 1.06 / 1.08 (§5.3). |
| `pressable()` | **Rewrite** | No press scale. Phone: neutral rows use the ripple; ports and job buttons swap to their fill or shade while pressed. TV: `state_pressed` shade. |
| `haptic()` | **Extend** | `haptic(v, kind)` with `TAP`, `TICK`, `CONFIRM`, `REJECT` (§2.8). |
| `forWhiteText()` | **Delete** | Text on every fill is fixed per `Job` (§2.3) and pre-verified (§7.1). |
| `primaryButton()` | **Restyle** as `jobButton(ctx, label, job, icon?)` | Solid `job.fill`, `job.on` text `T_BUTTON`, 6 dp radius, height 52 (TV 56), padding 20 h, no glow, no gradient. Pressed: `job.shade`. Disabled: `SURFACE_2` fill with `INK_3` text. TV rest state: `SURFACE_2` with a 6×24 key block (§5.3). |
| `secondaryButton()` | **Restyle** | Transparent, 1 px `LINE_STRONG` outline, `INK` text, 6 dp, height 52 (TV 56). |
| (new) `quietButton()` | **Add** | Text only, `T_BUTTON` `INK` (or `TXT_DANGER` for "Stop"), 48 dp touch target, no outline. Used for "All", "Edit", "Select", "Scan", "Paste". |
| `iconButton()` | **Restyle** | No circle background. 48 dp target (TV 56), 24 dp icon `INK`. TV focus: `INK` fill, 6 dp radius, scale 1.06. |
| `card()` | **Delete** | Use `section()` + rows. Sheets use `panel()` (below). |
| (new) `panel()` | **Add** | `SURFACE` fill, 0 radius (sheets add 12 dp top corners), 1 px `LINE` top edge. |
| `SheenLayout`, `actionCard()`, `tile()` | **Delete** | |
| `row()` | **Restyle** | No badge: bare 20 dp icon (`INK_2`) or a lead view. Min height 56/64 (TV 64). Padding = gutter horizontally. Title `T_ROW`, subtitle `T_META` (max 2 lines). Optional trailing view. `iconTint` param removed. |
| (new) `portRow(ctx, number, device, trailing)` | **Add** | §4.1 anatomy. Pressed/selected = full `C_SEND` row fill with inverted plate. `contentDescription` per §7. |
| (new) `plate(ctx, glyph, fill, on, sizeDp)` | **Add**, replaces `avatar()` and the row badges | Solid square, 6 dp radius, glyph in `on`. Has `invert()` for filled rows. |
| `avatar()` | **Delete** | → `plate` via `Theme.plateFor(type)`. |
| (new) `statusBlock(ctx, job?, title, meta)` | **Add** | Full-bleed 72 dp colour block (Receive screen, WebShare status, failure states). `job == null` → neutral `SURFACE_2`. |
| `chip()` | **Replace** → `tag(ctx, text)` | `T_MONO`, `INK_2`, 1 px `LINE_STRONG`, 6 dp, no fill. An action variant (`Install`, `Copy`) = small secondary button, 36 dp tall. |
| `dot()` | **Keep** as `led(ctx, state)` | 8 dp. `C_OK` (ready on Wi‑Fi), `C_RECEIVE` (visible beyond Wi‑Fi), `C_DANGER` (can't receive), hollow `LINE_STRONG` ring (offline). |
| `ToggleSwitch`, `toggleRow()` | **Restyle** | §4.8. No gradient shader. Knob slides in `D_TAP` with `EASE_MOVE`, instant when animations are off. |
| (new) `radioRow()`, `checkRow(job)` | **Add** | 22 dp square checkbox / 22 dp ring radio. Settings radios: `INK` dot. Checkboxes: checked = `job.fill` + `job.on` mark (orange in send contexts, ultramarine for "Always accept"). |
| (new) `stepper()` | **Add** | §4.8. |
| `spacer()` | Keep | |
| `divider()` | **Replace** → `rule()` | `LINE`, hairline thickness, full-bleed. |
| `header()` | **Restyle** | 56 dp. Back = bare `iconButton`, title `T_TITLE`, trailing quiet button. |
| `scrollPage()` | **Restyle** | No horizontal padding (full-bleed rules). TV: top padding 27 dp. Bottom padding = dock height + insets. |
| `applyInsets`, `insetsOf`, `currentInsets` | Keep | |
| `snackbar()` | **Restyle** | `INK` fill, `BG` text `T_BODY`, 6 dp, no glow. The action is a small solid block in its job colour (default `C_SEND`; "Open" on a received link = `C_TEXT`) with `job.on` text, so it passes on both the light and dark snackbar. Slide 8 dp + fade `D_STATE`. |
| `segmented()` | **Restyle** | 1 px `LINE_STRONG` outline, 6 dp outer radius. Selected segment `INK` fill with `BG` text. No sliding gradient pill; instant swap. |
| (new) `DotReadout` | **Add** (`DotReadout.kt`) | §2.9. |
| (new) `SegmentMeter` | **Add** (`SegmentMeter.kt`) | §4.6. Draws N rects with no allocations. Colours come from the panel's `Job` (`on` filled, `shade` empty). Has `setProgress(done, resumedAt)`. |
| (new) `TransferPanel` | **Add** | Full-bleed block that hosts the title, `DotReadout` and `SegmentMeter`, and takes a `Job` (SEND/RECEIVE/OK/DANGER, or null for cancelled). The panel colour switches instantly on a state change. |
| (new) `ScanTicks` | **Add** | Three squares, §2.8. Replaces `Anim.pulse` and the `ProgressBar` spinners. |
| (new) `Dock`, `TransferStrip` | **Add** | §4.1, §4.6. The strip is a solid `Job` fill. |
| (new) `tabs(ctx, items: List<Pair<String, Job?>>)` | **Add**, replaces the picker `TabBar` and History filters | Selected tab = solid block in its `Job` (or `INK`). Others are `INK_2` text. |

### 6.3 Other files

| File | Action |
|---|---|
| `Backdrop.kt` | **Delete.** Remove it from MainActivity, Send, Receive, WebShare, Picker and Browser. Roots just set `BG`. |
| `RadarView.kt` | **Delete.** Remove from Send and Receive. |
| `Confetti.kt` | **Delete.** |
| `ProgressRing.kt` | **Delete.** Transfer → `DotReadout` + `SegmentMeter`. Offer countdown → top bar (§4.5). Any spinner → `ScanTicks`. |
| `Anim.kt` | Keep `enter` (8 dp, `D_STATE`), `crossfade`, `screenIn`/`screenOut` (§2.8), `shake` (input validation only). **Delete** `stagger`, `pop`, `pulse`, `countTo`. |
| `Sheets.kt` | Keep its mechanics. Restyle: `panel()` surface, 12 dp top corners, no handle pill (the drag still works; the 1 px top edge is enough). TV/wide: centre overlay 600 dp. Durations `D_SHEET`/`D_SHEET_OUT`. |
| `QrView.kt` | Always black on a white plate with a 16 dp quiet zone, 0 radius. |
| `PickerActivity.kt` `TabBar` | Replace with `Ui.tabs` (solid category-colour block on the selected tab). TV: left rail with key blocks. |
| `PickUi.FloatingBar` | Becomes the standard `Dock` (no elevation, no rounded floating pill). |
| `QrScanActivity.PillButton` | Restyle to outlined white 6 dp buttons on black. |
| `Thumbs.kt` | When there's no real thumbnail, draw the category `plate` (`Theme.plateFor`). Remove the 15% category tints. |
| `HomeScreen.kt` | Rebuild per §4.1. |
| `TransferUi.kt` | Rebuild with `TransferPanel` per §4.5/§4.6 (state-coloured panel). Add the code readout, trust checkbox and text offer. |
| `WebPage.kt` (the browser page) | Out of scope here, but should adopt the same tokens in a later pass so the two match. |

---

## 7. Accessibility

### 7.1 Contrast (WCAG 2.x, computed)

| Pair | Ratio | Passes |
|---|---|---|
| `INK #161513` on `BG #F3F1EC` | **16.2:1** | AAA |
| `INK` on `SURFACE #FBFAF7` | **17.5:1** | AAA |
| `INK_2 #5B5852` on `BG` | **6.3:1** | AA (all sizes) |
| `INK_2` on `SURFACE_2 #E7E4DD` | **5.6:1** | AA |
| `INK_3 #8C887F` on `BG` | **3.1:1** | AA large text and UI outlines only; disabled text is exempt |
| `LINE_STRONG` on `BG` (control outlines) | **3.1:1** | 1.4.11 non-text ≥ 3:1 |
| Dark `INK #ECEAE4` on `BG #0F0F0E` | **15.9:1** | AAA |
| Dark `INK` on `SURFACE #191917` | **14.6:1** | AAA |
| Dark `INK_2 #A29F98` on `BG` | **7.3:1** | AAA |
| Dark `INK_2` on `SURFACE_2 #232321` | **6.0:1** | AA |
| Dark `INK_3 #6F6C66` on `BG` | **3.7:1** | AA large / UI only |
| TV neutral focus: `#0F0F0E` on `INK #ECEAE4` | **15.9:1** | AAA |

**Text on the six fills.** These are identical in both themes, because fills and `ON_*` colours
don't change with theme. They apply to buttons, panels, strips, focused or selected rows, plates
and lit readout dots.

| Fill | Text/icons on it | Ratio | Passes |
|---|---|---|---|
| `C_SEND #FF5A1F` | `#161513` | **5.9:1** | AA (all sizes) |
| `C_RECEIVE #2E3BFF` | `#FFFFFF` | **6.6:1** | AA |
| `C_WEB #FFC21A` | `#161513` | **11.3:1** | AAA |
| `C_TEXT #FF5FA2` | `#161513` | **6.4:1** | AA |
| `C_OK #1FAE52` | `#161513` | **6.3:1** | AA |
| `C_DANGER #D7192F` | `#FFFFFF` | **5.2:1** | AA |
| Forbidden pairs | white on orange 3.1, white on yellow 1.6, white on pink 2.8, white on green 2.9, ink on ultramarine 2.8, ink on red 3.5 | — | **Never use** |

**Coloured text on neutral backgrounds** (errors, "Retry continues from…", links). Yellow and pink
are never used as text.

| Token | Light on `BG` / `SURFACE` | Dark on `BG` / `SURFACE` |
|---|---|---|
| `TXT_SEND` `#C53D0B` / `#FF5A1F` | **4.6 / 5.0** | **6.2 / 5.7** |
| `TXT_RECEIVE` `#2E3BFF` / `#8E97FF` | **5.8 / 6.3** | **7.3 / 6.7** |
| `TXT_OK` `#13753A` / `#1FAE52` | **5.1 / 5.5** | **6.6 / 6.1** |
| `TXT_DANGER` `#D7192F` / `#FF6B6B` | **4.6 / 4.9** | **6.9 / 6.3** |

**Fills against the page** (WCAG 1.4.11 non-text): orange 2.8, green 2.6 and pink 2.5 on paper
are below 3:1, but every filled button and panel is identified by its label text (AA above), so
the boundary contrast isn't what identifies the control. Ultramarine on off-black is 2.9 for the
same reason. Plates always carry a glyph at ≥ 5.2:1 against the plate.

Unlit readout dots are deliberately low (~1.3:1 against their panel). They're texture. The
lit digits are the content, at ≥ 5.2:1.

`LINE` rules (1.3:1) are decorative separators, which is allowed, and they never carry meaning
alone. **Colour is never the only cue:** each job colour has a label (Send, Receive, Accept),
done has ✓, errors have "!" plus words, selection has ✓, and device plates have a type glyph.
That covers colour-blind users. Orange/red and green/red pairs in particular always differ in
text colour (dark on orange and green, white on red) and in wording.

### 7.2 Targets and layout

- Touch targets ≥ **48×48 dp** (rows 56+, icon buttons 48, checkboxes inside 56 dp rows). TV
  focus targets ≥ 56 dp tall.
- Fonts in `sp` everywhere. Test at **200%** font scale (Android 14 non-linear scaling). Rows
  grow in height (no fixed heights, only minimums), titles wrap to 2 lines, and tags drop below
  the title when they don't fit. `DotReadout` doesn't scale with font, but the same value is
  always present as text in `T_META` ("46.2 MB/s · 0:14 left"), so nothing is lost.
- RTL: the manifest already supports it. Use `marginStart/End`, and mirror the countdown bar and
  chevrons.

### 7.3 TalkBack labels

- Port row: "Living room TV, TV, on Wi‑Fi, trusted. Double-tap to send." The port number isn't
  read aloud. In multi-select mode the row is a checkbox:
  `AccessibilityNodeInfo.isCheckable/isChecked`.
- Status line: "Visible on Home Wi‑Fi. Trusted devices send without asking."
- Readout: `contentDescription` "46.2 megabytes per second". The transfer **title** row is a
  polite live region updated at most **every 5 s** with "Sending to Pixel 8, 63 percent, 14
  seconds left". Never make the 4 Hz readout itself live.
- Code readout: "Confirmation code 0 4 2 7" (digits spaced so they are read one by one).
- Offer sheet: announce on open, "Pixel 8 wants to send 3 photos and 1 video, 48 megabytes.
  Code 0 4 2 7." Accept and Decline are buttons. The countdown text is not a live region; the
  sheet announces "Closes in 10 seconds" once.
- Icon buttons: "History", "Settings", "Back", "Copy network name", "Show password".
- Toggles keep the existing switch role in `toggleRow`. Radios report as radio buttons in a
  group "Accept files from".
- The animations-off setting (§2.8) doubles as reduced motion.

---

## 8. Build order

Each step ships on its own and leaves the app working. Steps 1–5 deliver ~80% of the visible
change.

1. **Tokens and theme mode.** Rewrite `Theme.kt` per §6.1 (`resolve()`, light/dark, new names).
   Add the `Job` enum with the six colours. Keep temporary aliases (`TEXT = INK`,
   `MUTED = INK_2`, `ACCENT/SEND_A = C_SEND`, `RECV_A = C_RECEIVE`, `WEB_A = C_WEB`, `CAT_* →
   plate colours`) so everything compiles. Add `values-night`, system bar icon colours and
   Appearance setting storage. *Result: the whole app turns paper/off-black with the new colour
   code at once.*
2. **Kill the decoration.** Delete `Backdrop`, `RadarView`, `Confetti`, `SheenLayout`, `glow()`,
   `FocusRing`, `SPRING`, `Anim.stagger/pulse/pop/countTo` and every gradient. Remove the call
   sites. *Result: no slop tells left; flat and fast on TV.*
3. **Primitives.** In `Ui.kt`: `text` (tnum + line height), `section`, `rule`, restyled `row`,
   buttons (`jobButton` solid fills, `secondary` outlined, `quiet`), `plate`, `statusBlock`,
   `tabs`, `tag`, `led`, toggle, `segmented`, `snackbar`, `header`, full-bleed `scrollPage`,
   `focusFill`, and haptic kinds. Delete `card`, `actionCard`, `tile`, `chip`, `avatar`, then fix the compile errors
   screen by screen with `section` + rows.
4. **Home** per §4.1: device-first ports, status line, Other ways, Recent (3), `Dock`
   [Receive ultramarine][Send orange], `TransferStrip`. Remove the Quick send tiles and the
   device card.
5. **Signature.** `DotReadout` + `SegmentMeter` + `TransferPanel`, then rebuild the transfer
   sheet (§4.6) with all states and their panel colours: waiting + code (orange), running (orange
   or ultramarine), resuming, done (green), failed + Retry (red), cancelled (neutral).
6. **Offer sheet** (§4.5): code readout, Always accept, Trusted tag, text offer, 1-second
   countdown bar.
7. **Choose devices** (§4.3) with ports, multi-select and v1-text gating. **Receive** (§4.4).
8. **Picker** (colour tabs, orange selection marks, Text tab), **Browser**, **QR scan** (§4.2, §4.9).
9. **Settings** (§4.8): Accept-files-from radios, Trusted devices sub-screen, Verify data,
   stepper, Appearance.
10. **History** screen (§4.7), and Recent on Home reading from it.
11. **TV pass** (§5): two-pane Home, Show my QR, centre overlays, colour-fill + scale focus and key blocks everywhere,
    explicit pane focus links, number keys 1–9, 48/27 safe margins, TV type sizes.
12. **System surfaces:** QS tile (§4.11), widget (§4.12), and a monochrome notification small icon
    (the system requires it). New launcher icon: an adaptive icon with a `#FF5A1F` orange
    background and a 5×7 dot-matrix down-arrow in `#161513` lit dots with `#DA4F1D` unlit dots.
    That's the sending panel itself, so the icon *is* the signature. TV banner 320×180:
    background `#0F0F0E`, the same orange mark as a 120 dp square on the left, the wordmark
    "FastDrop" in `#ECEAE4` `sans-serif-medium`, and a row of four 12 dp squares (orange,
    ultramarine, yellow, pink) under it as the colour-code signature.
13. **Copy sweep** (§2.10): every string in HomeScreen, SendScreen, ReceiveScreen, TransferUi,
    Settings, Picker, QR scan and WebShare. Then remove the temporary token aliases from step 1.
14. **Verify:** contrast spot-check against §7.1, TalkBack walk-through of send and receive,
    200% font scale, animations off, D-pad-only run on a real TV (focus never lost, nothing
    outside 48/27), and APK size before/after (expect a net **decrease**: ~1,000 lines of
    decorative views go, ~300 lines of readout/meter/ports come in).

Definition of done: no gradient, shadow, glow, tint (`alpha()` on a colour token), uppercase
label or card is left in `app/src`
(`grep -n "GradientDrawable(.*Orientation\|elevation =\|uppercase()\|Ui.card\|alpha(Theme.C_" app/src`
returns nothing outside the widget). Every colour use matches the "Where each colour may appear"
table in §2.3. Every screen in §4/§5 has its empty, loading, error and busy states.

---

## Sources

- [S1] Teenage Engineering design guide (reconstructed palette, mono type, spec grids, "two presses away"): https://blakecrosley.com/guides/design/teenage-engineering
- [S2] Nothing OS evolution (community post on using dot-matrix "more intentionally"): https://nothing.community/d/61774-nothing-os-evolution
- [S3] Nothing design language notes (Ndot used only for identifiers, not headings, buttons or body): https://www.shadcn.io/design/nothing ; Nothing OS 3 hands-on (dot font removed from settings headers and clock for readability): https://androidauthority.com/nothing-os-3-hands-on-3488739
- [S4] Quick Share full-screen redesign (Send/Receive tabs, device grid, QR corner button, "Everyone for 10 minutes"): https://9to5google.com/2025/09/24/quick-share-fullscreen-redesign/ ; Quick Share for Windows redesign: https://dataconomy.com/2025/11/04/google-rolls-out-redesigned-quick-share-app-for-windows
- [S5] Android TV: Design for TV ("instant and distinct feedback"): https://developer.android.com/design/ui/tv/guides/foundations/design-for-tv ; TV typography (Roboto default, larger type): https://developer.android.com/design/ui/tv/guides/styles/typography
- [S5b] Android TV layouts (960×540 dp, 48/27 dp overscan margins, 12-col grid, two-pane): https://developer.android.com/design/ui/tv/guides/styles/layouts
- [S5c] Android TV focus system (scale 1.025/1.05/1.1, border, glow, colour indication): https://developer.android.com/design/ui/tv/guides/styles/focus-system
- [S6] Smashing Magazine, Designing for TV (5% safe area, 24 px body, invert/scale/border focus, axis roles, avoid bright full-screen backgrounds, keyboard cost): https://smashingmagazine.com/2025/09/designing-tv-principles-patterns-practical-guidance/
- [S7] Amazon Fire TV design guidelines (inner 90% safe zone, 14 sp minimum body, avoid warm saturated colours, every element D-pad reachable): https://developer.amazon.com/docs/fire-tv/design-and-user-experience-guidelines.html
- [S8] LocalSend (tap a nearby device, PIN option, favourites/manual IP): https://localsend.org/ , https://github.com/localsend/localsend
- [S9] PairDrop (pair with a 6-digit code or QR, paste text then pick a recipient, persistent pairings): https://github.com/schlagmichdoch/PairDrop
- [S10] AI-slop tells (purple-to-blue gradient as the most recognisable tell, identical card grids, icon tiles): https://github.com/Laith0003/ux-skill/wiki/How-to-detect-AI-slop-in-your-design ; https://noqta.tn/en/blog/ai-design-slop-overused-ui-patterns-fix-2026
- [S11] Why AI defaults to purple gradients: https://prg.sh/ramblings/Why-Your-AI-Keeps-Building-the-Same-Purple-Gradient-Website ; https://superdesign.dev/blog/why-ai-design-looks-generic
- [S12] Material 3 easing and duration tokens (standard / decelerate / accelerate curves): https://m3.material.io/styles/motion/easing-and-duration/tokens-specs
- [S13] Android dynamic colour (API 31+, tonal palettes): https://developer.android.com/develop/ui/views/theming/dynamic-colors
- [S14] Quick Settings tiles (toggle, ≤ 2 tiles, 24 dp solid-white vector icon, 18-char label, `requestAddTileService`): https://developer.android.com/develop/ui/views/quicksettings-tiles
- [S15] What to avoid, SHAREit/Xender clutter (ads, app-store-like home, busy UI): https://www.techradar.com/reviews/shareit-file-transfer-app-review ; https://techzim.co.zw/greed-ads-and-bugs-are-ruining-shareit-here-are-some-alternatives/
- AirDrop recipients appear by device name in the share sheet's first row: https://www.cultofmac.com/how-to/how-to-airdrop ; NameDrop: https://www.macrumors.com/guide/ios-17-airdrop
