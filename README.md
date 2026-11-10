# Play_IPTV (Android TV)

Controls (while playing): UP = next channel, DOWN = previous channel, OK/LEFT/RIGHT = channel list, 0-9 = jump to channel number, BACK = close list / exit.

In the channel list: hold OK on a channel = add/remove favourite (see the "★ Favorites" group; Yellow/* key also works). Press UP from the list to reach the search box. "Volume boost" button (top of list) or VOL+ at max volume = boost up to 200%.

Player panel (v1.5): GREEN / BLUE / INFO key, or the "Audio & Subtitles" button at the top of the channel list = open the player panel (pause/play, ±10 s seek, LIVE, audio track, subtitles). CC / SUBTITLE key opens it on the subtitle list, AUDIO key on the audio list. Media keys (play/pause, rewind, fast-forward) work anywhere. Your audio language / subtitle choice is remembered for the next channels.

## APK GitHub-il build cheyyan
1. github.com -> New repository (public/private, any name) -> create.
2. Ee folder-ile ellaa files-um (hidden `.github` folder ulppede) upload cheyyuka (Add file -> Upload files) / git push.
3. Repository -> Actions tab -> "Build APK" -> Run workflow (push cheythaal automatic aayi run aakum).
4. 3-5 minute kazhinjal run open cheythu `Play_IPTV-apk` artifact download cheyyuka -> zip-il `Play_IPTV.apk` undaakum.
5. APK TV-yil install cheyyuka (USB / Send Files to TV / Downloader app).

## EPG (Program Guide)
iptv-org/epg oru ready-made link alla, athu guide.xml undaakkunna tool aanu. Athinu:
1. github.com/iptv-org/epg -> Fork.
2. Fork-inte root-il `channels.xml` undaakkuka (README-yile "How to use" nokki, nammude channels-inte site files `sites/` folder-ilninnu copy cheyyam).
3. `epg-workflow/epg.yml` fork-il `.github/workflows/epg.yml` aayi add cheyyuka.
4. Fork -> Settings -> Pages -> Source: "GitHub Actions". Actions-il "EPG" workflow run cheyyuka.
5. Link: `https://YOURNAME.github.io/epg/guide.xml` (repo name anusarichu maarum).
6. App project-il `Data.kt`-yile `Config.EPG_URL`-il aa link ittu push cheyyuka -> APK veendum build aakum.
