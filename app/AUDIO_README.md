把铃声文件放在这个目录，文件名必须与代码里一致（.ogg / .mp3 / .wav 均可）：

  temple_bell.*   寺院钟
  singing_bowl.*  颂钵
  gentle_chime.*  柔和铃
  wood_block.*    木鱼

没有放入对应文件时，App 会自动回退到系统通知铃声，仍能正常响铃提醒
（见 audio/BellPlayer.kt 的 playFallbackRingtone）。

铃声素材可在遵守授权的前提下从公开来源获取，例如：
  - nyxkn/meditation (GitHub) 的 bells/gongs 音频（README 中标注为 CC-BY-SA 4.0）
  - freesound.org 上标注为 CC0 的音效
请务必保留原作者署名并按许可协议使用。
