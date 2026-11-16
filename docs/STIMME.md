# Arius: Herkunft, Voicelines, Untertitel und Szenentexte

**Verbindliche Grenze:** Alle Texte gehören ausschließlich zu diesem Addon. Hermes, Hades, Zeus, Kronos, der Minotaurus
und alle anderen bestehenden Figuren bekommen **keine** neuen oder geänderten Zeilen, sprechen nicht mit Arius und
erkennen ihn nicht. Arius erwähnt sie nur als **Gerücht, Ortsbeobachtung oder Folge**, nie als Bekanntschaft. Nichts
hier setzt eine andere Mod voraus. Fehlt HHA-Fortschritt, sagt er nichts darüber.

Geprüfte Anknüpfungspunkte in den HHA-Texten (`assets/hha/lang`): der Olymp als Marmorgebirge mit einem Tempel, dessen
vier Steine Gaben verlangen; ein Bote, der seit Jahrtausenden nicht gefangen wurde; das schwarze Tor, hinter dem der
Himmel rot ist; die Stele „Der Hausherr empfängt jeden. Er verabschiedet nur niemanden.“ Vom Minotaurus (anderes Addon)
nutzt Arius nichts. Das hält ihn unabhängig.

## Herkunft *(Mod-Adaption, legt nichts für den Roman fest)*

- **Astoria** ist Arius' Heimat, weit jenseits der hier bekannten Länder. Er nennt sie selten.
- **Anlass:** Die alte weiße Drachin **Nivaris** hat Astoria vor langer Zeit verlassen und ist weiter geflogen, als
  irgendeine Karte reicht. Arius ist ihrer Spur gefolgt, über Meere und durch Länder mit fremden Göttern, und hat sie
  schlafend in einem Gletscher gefunden. Er hat den Ort nach seinem Heimatdorf **Eirholm** benannt, weil er sonst keinen
  Namen hatte.
- **Konflikt:** Nivaris heilt im Schlaf. Das **Siegel von Eirholm** ist nach einer Methode des **White Throne**
  geschmiedet; dieses Wissen ist bis in diese Welt gelangt. Jedes gebrochene Siegel weckt Nivaris ein Stück. Wer mit
  einem kommt, ist für Arius jemand, der Menschen und Drachen als Waffen benutzt. Er prüft ihn.
- **Auflösung:** Im Finale erkennt er, dass der Spieler keine Waffe ist, und gibt ihm einen Teil dessen, was er
  schützt.

## Darstellung

- **Untertitel** (Sprecherzeile „Arius“ in Eisblau, Text weiß) unten mittig. Sie erscheinen immer, auch ohne Ton. Länge
  nach Textlänge, höchstens 5 s.
- **Szenentexte** (kursiv, grau, ohne Sprecher) beschreiben kurz, was man sieht oder hört. Sie stehen nur bei
  inszenierten Momenten.
- **Voicelines:** Für jede Zeile gibt es ein optionales Sound-Event `hha_arius:voice.<de|en>.<id>`. Fehlt die Datei,
  bleibt nur der Untertitel. Die Stimme würde wie bei HHA mit ElevenLabs erzeugt (`tools/assets/voice_lines.py`, Key nur
  per Umgebungsvariable). **Stand 1.0.0: keine Stimmen erzeugt (Entscheidung des Nutzers: erst nur Text).**
- **Im Kampf** spricht Arius höchstens alle 45 s und nie während einer laufenden Ansage gegen den Untertitel-Empfänger.
  Beim Schnitt, beim Tritt und bei der Verwandlung schweigt er.

## Zeilen

Ton: ruhig, knapp, konkret. Trockener Humor nur dort, wo er die Spannung nicht bricht.

| # | ID | Auslöser | Deutsch | English | Tonfall | Wiederholung |
|---|---|---|---|---|---|---|
| 1 | `intro.seal` | Ankunft, erste Begegnung: Arius sitzt auf den Altarstufen, sobald ein Spieler ≤ 16 Blöcke heran ist | Ein Siegel des White Throne. So weit weg von zu Hause. | A White Throne seal. This far from home. | leise, mehr zu sich selbst | einmal je Spieler (gespeichert) |
| 2 | `intro.seas` | direkt danach | Ich bin über drei Meere gegangen, um die los zu sein. | I crossed three seas to be rid of them. | trocken, müde | wie 1 |
| 3 | `intro.reason` | er steht auf | Unter diesem Eis schläft jemand, und jedes Siegel weckt sie ein Stück. | Someone sleeps under this ice. Every seal wakes her a little. | sachlich | wie 1 |
| 4 | `intro.test` | er zieht Solvane, Kampfbeginn | Leg es weg. … Nein? Dann zeig mir, wofür du es trägst. | Put it down. … No? Then show me what you carry it for. | ruhig, keine Drohung | wie 1 |
| 5 | `serious` | Abschnitt I, Arius' Leben fällt unter 40 % | …Gut. | …Good. | anerkennend, kurz | jeder Kampf |
| — | *(Szene)* `scene.cut_after` | 2 s nach dem Schnitt, wenn der Tempel bricht | *Erst jetzt hörst du den Schnitt.* | *Only now do you hear the cut.* | — | jeder Kampf |
| 5b | `dragon.wake` | Auftritt an der Nadel: der Schneesturm verdichtet sich, bevor die Windungen sichtbar werden | Hört ihr das? Das ist nicht der Wind. | Do you hear that? That isn't the wind. | leise, angespannt | jeder Kampf |
| 6 | `dragon.name` | Abschnitt II, Nivaris landet nach dem Auftritt an der Nadel vor den Landeplätzen | Nivaris. | Nivaris. | fast zärtlich | jeder Kampf |
| — | *(Szene)* `scene.dragon` | gleichzeitig | *Ein Auge golden, eines eisblau. Wie seine.* | *One eye gold, one ice-blue. Like his.* | — | erste Begegnung je Spieler |
| 7 | `dragon.polite` | 4 s danach, Arius setzt sich auf die Tempelhälfte | Ich bin ihr bis hierher gefolgt. Sie war vor mir da. Sei höflich. | I followed her all the way here. She was here first. Be polite. | trocken | jeder Kampf; bei Wiederholung nur „Sei höflich.“ (`dragon.polite_short`) |
| 8 | `home.astoria` | Abschnitt II, wenn Nivaris zum ersten Mal landet und Arius zusieht | Wo ich herkomme, gibt es ein Dorf namens Eirholm. Dieser Ort hatte keinen Namen. Jetzt hat er meinen. | Where I come from there's a village called Eirholm. This place had no name. Now it has mine. | ruhig, Heimweh nur angedeutet | erste Begegnung je Spieler |
| 9 | `home.far` | stattdessen bei späteren Kämpfen | Astoria ist weiter weg, als eure Karten reichen. Ich habe nachgesehen. | Astoria is farther than your maps go. I checked. | trocken | Wechsel mit 10–12 |
| 10 | `rumor.olympus` | Abschnitt II, zweite Bodenphase von Nivaris (Arius sieht zu) | Auf dem Weg hierher stand ein Tempel auf einem Marmorberg. Vier Steine wollten Geschenke von mir. Ich hatte nur Schnee dabei. | On my way here there was a temple on a marble mountain. Four stones wanted gifts from me. All I had was snow. | trocken | **eine** Erwähnung je Kampf aus 10–12, reihum je Spieler, ohne Wiederholung, bis alle gehört sind |
| 11 | `rumor.messenger` | wie 10 | Ein Bote mit Flügeln an den Sandalen ist zweimal an mir vorbei. Angehalten hat er nicht. Ich glaube, das tut er nie. | A messenger with wings on his sandals passed me twice. He never stopped. I don't think he does. | beobachtend | wie 10 |
| 12 | `rumor.gate` | wie 10 | Man hat mir von einem schwarzen Tor erzählt, hinter dem der Himmel rot ist, und von einem Hausherrn, der jeden empfängt. Ich bin oben geblieben. | They told me about a black gate with a red sky behind it, and a host who receives everyone. I stayed up here. | ernst, mit einem Hauch Humor im letzten Satz | wie 10 |
| 13 | `fall.together` | Übergang II → III, Arius pfeift, kurz bevor Nivaris in den Gletscher stürzt | Nivaris. Zusammen. | Nivaris. Together. | entschlossen | jeder Kampf |
| — | *(Szene)* `scene.collapse` | der Boden bricht | *Der Gletscher gibt nach.* | *The glacier gives way.* | — | jeder Kampf |
| — | *(Szene)* `scene.transform` | Mitte der Verwandlung, Arius schweigt | *Die Rüstung zerfällt zu Frost. Darunter trägt er Weiß.* | *The armour falls apart into frost. Underneath, he wears white.* | — | erste Begegnung je Spieler, sonst ohne Text |
| — | *(Szene)* `scene.white_hole` | weißes Loch entsteht | *Ein Licht, das nicht anzieht, sondern drückt.* | *A light that does not pull. It pushes.* | — | erste Begegnung je Spieler |
| 14 | `finale` | Finale beginnt (Sturm lässt nach, Nivaris legt sich hin) | Kein Sturm mehr. Nur du und ich. | No more storm. Just you and me. | leise, ernst | jeder Kampf |
| 15 | `end.weapon` | Sieg, Arius kniet | Du bist keine Waffe. Eine Waffe hätte nicht aufgehört, als es vorbei war. | You're not a weapon. A weapon wouldn't have stopped when it was over. | ehrlich, erschöpft | jeder Sieg |
| 16 | `end.take` | er steht auf, die Belohnung erscheint | Nimm das. Und das nächste Siegel wirfst du ins Meer. | Take this. And throw the next seal into the sea. | trocken | jeder Sieg |
| 17 | `end.heaven` | nur wenn der Duellpartner ein Teil der HHA-Himmelsrüstung trägt (über HHAs Set-Registry geprüft; nur das Item, kein angeblicher Sieg) | Die Rüstung kommt vom Marmorberg, oder? Hoffentlich haben sie dich dort gut bezahlt. | That armour's from the marble mountain, isn't it? I hope they paid you well. | trocken | höchstens einmal je Spieler |
| 18 | `end.hell` | statt 17, wenn ein Teil der HHA-Höllenrüstung getragen wird | Schwarzes Netherit mit Seelenfeuer. Ich frage lieber nicht, woher. | Black netherite with soulfire. I'd rather not ask where from. | trocken | höchstens einmal je Spieler |
| 19 | `end.nivaris` | letzte Zeile, Blick zu Nivaris | Sie wird mich jetzt eine Woche lang anschweigen. | She won't speak to me for a week now. | trockener Humor, gelöst | erster Sieg je Spieler |
| 20 | `defeat` | alle k. o. | Geh nach Hause. Das Siegel nimm mit. Es gehört nicht hierher. | Go home. Take the seal with you. It doesn't belong here. | ruhig, nicht spöttisch | jede Niederlage |
| 21 | `again.1` | verkürzte Einleitung: Spieler kennt das Intro | Du schon wieder. | You again. | trocken | Wechsel 21–23 |
| 22 | `again.2` | wie 21 | Noch ein Siegel. Ihr lernt nicht. | Another seal. You people don't learn. | trocken | wie 21 |
| 23 | `again.3` | wie 21, nach mindestens einem Sieg dieses Spielers | Gut. Diesmal ohne Reden. | Fine. No talking this time. | knapp | wie 21 |
| 24 | `echo` | Training über das Echo von Eirholm | Nur ein Abschnitt. Danach ist Ruhe. | Just one part. Then we're done. | sachlich | jedes Training |

Szenentext beim Benutzen des Siegels (ohne Arius): *Der Schnee fällt plötzlich nach oben.* / *Snow suddenly falls upward.*

## Nicht gesprochen (bewusstes Schweigen)
Der Schnitt, der Tritt durchs Tor, die Luftkombination, die Verwandlung, das weiße Loch und die drei Austausche im
Finale haben **keine** Zeile. Dort wirken Stille und Ton.
