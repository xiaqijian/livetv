## 🌐 語言 / Languages

- [🇨🇳 中文說明](README.md)
- [🇺🇸 English Version](README.en.md)
# Your TV：Android Live TV APK
Support Android 6.0 (API23) and above<br>
Combines the functions of my-tv/my-tv-0/my-tv-1/mytv-android/WebViewTVLive and other projects.<br>
IPTV/web video player android APK software, customizable sources(
(Supports webview:// format web video source)), <br>
IPTV supports picture-in-picture and off-screen playback.<br>
[yourtv](https://github.com/horsemail/yourtv)
<br>
## Online encryption and decryption: (TVBox-compatible interface source encryption and decryption)
https://yourtvcrypto.horsenma.net<br>
The encryption and decryption logic is identical to the project.<br>

Telegram Group<br>
https://t.me/yourtvapp<br>
<img src="./screenshots/appreciate.jpg" alt="image" width=200 /><br><br>
<img src="./screenshots/527.jpg" alt="image"/><br><br>
<img src="./screenshots/090901.jpg" alt="image"/><br><br>
<img src="./screenshots/090902.jpg" alt="image"/><br><br>
### The first official release was on April 21, 2025. Instructions for use:<br>
The APP itself can automatically switch the live source, and you can also switch the live source manually. <br>

* If you encounter a problem, you can first consider restarting/restoring to default/clearing data/reinstalling to solve it yourself

Download and install [releases](https://github.com/horsemail/yourtv)

Note that "*-kitkat" is an Android 4.4 compatible version

More download addresses

## Others

It is recommended to install via ADB:

```shell
adb install YourTV.apk
```

Xiaomi TV can be installed using Xiaomi TV Assistant

## Frequently Asked Questions

* Why does the video source text return to the original configuration after opening the app again after configuring it remotely? <br>

If "Update video source after app launch" is turned on and the video source address exists, it will be automatically updated, which may overwrite the saved video source text. <br>

* Notes for compiling APP by yourself: <br>
1. Resource files need to be confirmed one by one and set to their own information, especially cloudflare.txt/github_private.txt/sources.txt<br>
Need to use the encryption and decryption tool website https://yourtvcrypto.horsenma.net to encrypt and store. <br>
2. The APK file I uploaded may not be synchronized with the source code. The APK file is relatively new, and the source code update is generally a few days behind. Please check it carefully. <br>
3. The encryption and decryption logic used in the APK file I uploaded is different from the encryption and decryption logic in the project: https://yourtvcrypto.horsenma.net, in order to protect my private resource information. <br>

## Thanks

[live](https://github.com/fanmingming/live)<br>
[my-tv-0](https://github.com/lizongying/my-tv-0)<br>
[my-tv-1](https://github.com/lizongying/my-tv-1)<br>
