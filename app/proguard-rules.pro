# Encoder MP3 (jump3r): mantém tudo e ignora classes javax.sound que não existem no Android
-keep class de.sciss.jump3r.** { *; }
-dontwarn javax.sound.**
-dontwarn de.sciss.jump3r.**
