# MapDraw – dessiner sur la carte (Minecraft Java 26.3, Fabric)

Ce mod permet de dessiner sur la carte, d'afficher les tracés dans le monde en blocs fantômes translucides, et de les partager entre joueurs.

- **Avec JourneyMap** : on dessine directement sur la carte plein écran de JourneyMap. Les tracés apparaissent aussi sur la minimap.
- **Sans JourneyMap** : le mod fournit sa propre carte de dessin (touche `N`).
- **Un seul `.jar`**, à installer sur le client et sur le serveur. Si le serveur n'a pas le mod, tout fonctionne en local, sans synchronisation.

---

## 1. Compiler le `.jar`

### Prérequis
- **JDK 25.** Sur un Mac M1, prendre la version « macOS / ARM64 » de Temurin 25 : https://adoptium.net
- Une connexion internet : Gradle télécharge Minecraft, Fabric et les dépendances au premier build.

### Commande
Dans le dossier du projet :

```bash
chmod +x gradlew
./gradlew build
```

Le mod se trouve ensuite dans `build/libs/mapdraw-1.0.0.jar`. Ignore le fichier `-sources.jar`.

### Alternative sans rien installer : GitHub Actions
Pousse le projet dans un dépôt GitHub. Le workflow `.github/workflows/build.yml` compile le mod à chaque push. Le `.jar` se télécharge ensuite dans l'onglet **Actions**, sur le dernier build, rubrique **Artifacts**.

### Si une dépendance n'est pas trouvée
- **JourneyMap API introuvable** : dans `gradle.properties`, remplace `journeymap_api_version=26.3-2.0.0` par `26.3-2.0.0-SNAPSHOT`.
- **Fabric API** : vérifie la dernière version pour 26.3 sur https://fabricmc.net/develop et mets à jour `fabric_api_version`.
- Pour toute autre erreur de compilation, copie le message complet : il suffit souvent d'ajuster un nom de méthode Minecraft.

---

## 2. Dépendances

| Dépendance | Version | Rôle | Où l'installer |
|---|---|---|---|
| Fabric Loader | ≥ 0.19.5 | chargeur de mods | client + serveur |
| Fabric API | 0.166.2+26.3 ou plus récent | événements, réseau, rendu | client + serveur (**obligatoire**) |
| JourneyMap | version 26.3 (API v2.0.0) | dessin sur la carte JM, overlays | client (**optionnel**) |
| Mod Menu | 21.0.0 (26.3) | bouton « Configurer » | client (**optionnel**) |

Cloth Config n'est **pas** nécessaire : le menu de réglages est intégré au mod. JourneyMap et Mod Menu sont des dépendances « souples » : le mod se lance sans eux.

---

## 3. Installation

### Client (ton Mac)
1. Installe Fabric Loader pour Minecraft 26.3.
2. Copie dans `.minecraft/mods/` : `mapdraw-1.0.0.jar`, Fabric API, et JourneyMap (et Mod Menu si tu veux).

### Serveur
1. Installe le serveur Fabric 26.3 avec le *Fabric server launcher* : https://fabricmc.net/use/server
2. Copie dans le dossier `mods/` du serveur : `mapdraw-1.0.0.jar` et **Fabric API**. JourneyMap n'est pas nécessaire côté serveur pour MapDraw.
3. Redémarre le serveur. Le journal doit afficher `MapDraw initialisé (protocole 1)`.
4. Les calques partagés sont enregistrés dans `<monde>/mapdraw/<dimension>.json`, par exemple `world/mapdraw/minecraft_overworld.json`. Pour sauvegarder les tracés, il suffit de sauvegarder ce dossier.

### Serveur Paper
Paper ne charge pas les mods Fabric : utilise le **plugin** `mapdraw-paper-1.0.0.jar` (même release), à mettre dans `plugins/`.
Il parle le même protocole que le mod : les joueurs gardent le mod MapDraw côté client. Les calques partagés sont stockés dans
`plugins/MapDraw/layers/<dimension>.json` ; chaque monde (multi-mondes) a sa propre dimension, donc ses propres calques.
Fabric API n'est pas nécessaire côté serveur Paper.

En solo, le serveur intégré embarque le mod : le partage fonctionne aussi en LAN.

---

## 4. Utilisation

### Touches (modifiables dans Options > Commandes > MapDraw)
| Touche | Action |
|---|---|
| `N` | Ouvrir l'écran MapDraw (outils, calques, carte intégrée) |
| `K` | Afficher / masquer les tracés dans le monde |
| `Ctrl+Z` / `Cmd+Z` | Annuler |
| `Ctrl+Y` / `Cmd+Y` (ou `Ctrl+Maj+Z`) | Rétablir |

Les raccourcis suivent la disposition du clavier : `Ctrl+Z` fonctionne aussi en AZERTY.

### Sur la carte JourneyMap
1. Ouvre la carte plein écran de JourneyMap.
2. Dans la barre de boutons des add-ons, clique **« Dessin : OFF »** pour passer en **ON**.
3. Le clic gauche dessine. En mode dessin, le glisser gauche ne déplace plus la carte : repasse sur OFF pour naviguer.
4. Le bouton **« Outils MapDraw »** ouvre la palette : outil, couleur, épaisseur et calques. À la fermeture, tu reviens sur JourneyMap.

### Outils
- **Main levée** : clic gauche maintenu.
- **Ligne droite** : un clic sur le départ, un clic sur l'arrivée, avec aperçu entre les deux. Le clic droit annule.
- **Gomme** : efface les blocs du calque actif.
- **Rectangle** et **Cercle / ellipse** : un clic sur un coin, un clic sur le coin opposé (aperçu en direct, clic droit : annuler).
- **Polygone** : un clic par sommet ; pour fermer, recliquer le premier point, faire un clic droit ou appuyer sur Entrée. Retour arrière retire le dernier point.
- **Formes : surface teintée / contour seul** : en mode surface, l'intérieur est teinté (translucide sur la carte, plaque fine posée au sol dans le monde) et le contour reste net. Idéal pour placer des maisons, des parcelles, des quartiers.
- **Couleur** : palette de 12 couleurs ou code hexadécimal (`#E53935`).
- **Épaisseur** : de 1 à 5 blocs, avec un pinceau carré.

Chaque point est converti en coordonnées de bloc entières. Les segments sont rastérisés avec un Bresenham « 4-connexe » : à chaque pas en diagonale, un bloc intermédiaire est ajouté, donc **une diagonale ne laisse jamais de trou**.

### Calques
- La liste n'affiche que les calques de la dimension actuelle.
- Le `●` à gauche affiche ou masque le calque. Le masquage est local et ne supprime rien, y compris pour les calques des autres joueurs.
- Un clic sur le nom rend le calque actif.
- **Nouveau / Renommer** utilisent le champ texte sous la liste.
- **Calque dans le monde : visible / caché** : le calque reste sur la carte mais n'apparaît plus dans le monde (réglage personnel). Marqué « (carte) » dans la liste.
- **Partager / Rendre privé** : réservé au propriétaire, et seulement si le serveur a le mod.
- **Supprimer** et **Tout effacer** demandent une confirmation. « Tout effacer » peut être annulé avec `Ctrl+Z`.
- **Exporter** crée un `.json` dans `config/mapdraw/exports/`. **Importer** recrée ce calque comme calque privé dans la dimension actuelle. Ça sert de secours quand le serveur n'a pas le mod.
- Badges : `P` = ton calque partagé ; un pseudo = calque partagé d'un autre joueur. Un nom grisé signifie que le calque est en lecture seule.

### Droits
Seul le propriétaire d'un calque partagé, ou un OP, peut le modifier, le renommer ou le supprimer. Le serveur vérifie chaque paquet : une modification refusée est annulée chez son auteur.

---

## 5. Affichage dans le monde

- Chaque bloc du tracé apparaît comme un **bloc fantôme translucide** de la couleur du trait. C'est purement visuel : il n'y a pas de vrai bloc et aucun matériau.
- **Le tracé suit la surface** : pour chaque colonne (X, Z), le bloc fantôme se place juste au-dessus du bloc solide le plus haut. Les feuillages et l'herbe haute sont ignorés (équivalent de `MOTION_BLOCKING_NO_LEAVES`). Il n'y a jamais de tracé souterrain.
- La hauteur est recalculée quand un chunk se charge. Le terrain sous les tracés est aussi revérifié en continu, environ 1 500 colonnes par tick, ce qui fait 10 000 blocs en moins d'une demi-seconde.
- Réglages (`N` puis **Réglages**, ou Mod Menu) : opacité, distance d'affichage max, visible à travers les blocs ou non, opacité sur JourneyMap, mode de rendu.

### Performances
- Un **mesh par chunk**, reconstruit uniquement quand un tracé ou le terrain change, jamais à chaque image.
- En mode **GPU** (par défaut), chaque mesh est envoyé une seule fois dans un buffer GPU persistant, puis redessiné tel quel : une passe de rendu, un appel de dessin par chunk.
- Les faces internes entre blocs fantômes voisins de même hauteur ne sont pas dessinées.
- **macOS / M1 (OpenGL 4.1)** : aucun shader personnalisé. Le mod réutilise le shader vanilla des boîtes de debug et ne change que l'état de profondeur et de transparence.
- **Sodium** : le rendu passe par les événements de rendu de Fabric API, qui restent actifs avec Sodium.
- Si le rendu GPU échoue sur une configuration particulière, le mod passe tout seul en **mode compatibilité** (rendu vanilla des boîtes de debug) et l'indique dans le chat. Dans ce mode, l'option « à travers les blocs » n'a pas d'effet.

---

## 6. Réseau

- À la connexion, le serveur envoie au joueur tous les calques partagés, toutes dimensions confondues.
- Chaque modification est mise en file. Le client envoie au plus 2 000 blocs par paquet, une fois par tick (50 ms). Le serveur regroupe ce qu'il reçoit pendant un tick et le diffuse **à la fin de ce tick** aux autres joueurs.
- Délai attendu entre A et B : 2 ticks plus la latence réseau, soit environ 100 à 200 ms. C'est bien sous la seconde.

---

## 7. Procédure de test (critères de validation)

1. **Diagonale continue** : avec l'outil Ligne, trace de (0, 0) à (20, 20). Dans le monde, les blocs fantômes forment un escalier continu, chaque bloc touchant le suivant par une face. Ce point est aussi couvert par `./gradlew test` (`RasterTest`).
2. **Relief** : trace une ligne qui traverse une colline. Chaque bloc fantôme repose sur le bloc solide le plus haut. Casse ou pose un bloc sous le tracé : le fantôme se déplace en moins d'une seconde.
3. **Synchro A → B** : le joueur A crée un calque, clique **Partager**, puis dessine. Le tracé apparaît chez B en moins d'une seconde.
4. **Droits** : B sélectionne le calque de A. Il est grisé, le dessin et la suppression sont refusés avec un message. Même un paquet forcé serait rejeté par le serveur.
5. **Persistance** : arrête le serveur (`stop`), redémarre-le, reconnecte-toi : les calques partagés sont toujours là. Ils sont lus depuis `world/mapdraw/*.json`.
6. **Mode local** : connecte-toi à un serveur sans MapDraw. Le dessin fonctionne et le bouton Partager est désactivé. Exporte un calque, puis importe-le dans un autre monde.

---

## 8. Fichiers créés

| Fichier | Contenu |
|---|---|
| `config/mapdraw.json` | réglages client |
| `config/mapdraw/worlds/<monde ou serveur>/<dimension>.json` | calques privés |
| `config/mapdraw/worlds/<…>/view.json` | calques masqués, calque actif |
| `config/mapdraw/exports/*.json` | exports |
| `<monde serveur>/mapdraw/<dimension>.json` | calques partagés |

---

## 9. Points à surveiller au premier lancement

Le code a été écrit à partir des sources officielles de Fabric API 26.3, de l'API JourneyMap (branche `26.3_2.0`) et de mods déjà portés en 26.3. Il n'a cependant pas pu être compilé dans l'environnement où il a été écrit, qui n'avait pas accès aux dépôts Mojang et Fabric. À vérifier en priorité :

- **Compilation** : une éventuelle erreur portera sur un nom de méthode Minecraft 26.3. Il suffit de copier le message pour la corriger.
- **Boutons souris JourneyMap** : le mod lit le vrai bouton via l'API écran de Fabric, mais si le dessin ne réagit pas sur la carte JM, signale-le.
- **Profondeur** : Minecraft 26.x utilise une profondeur inversée (reverse-Z). Si les blocs fantômes restent invisibles en mode normal mais apparaissent avec « à travers les blocs », c'est ce réglage qu'il faudra inverser dans `GhostPipelines.java`.

## Structure du code

```
src/main/java/fr/thivant/mapdraw/
  MapDraw.java                 point d'entrée commun
  core/                        modèle pur Java (sans Minecraft) : CellKey, Raster, LayerData, LayerMeta, LayerJson
  net/MapDrawPayloads.java     paquets réseau
  server/                      ServerSync (droits, diffusion groupée), ServerStore (JSON par dimension)
src/client/java/fr/thivant/mapdraw/client/
  MapDrawClient.java           point d'entrée client, touches, ticks
  LayerManager.java            calques, annuler/rétablir, file réseau (sans Minecraft, testable)
  DrawTools.java               main levée, ligne, gomme
  Terrain.java                 hauteur de surface (feuillages ignorés)
  render/                      GhostRenderer (mesh par chunk, buffers GPU), GhostPipelines
  gui/                         écran MapDraw, réglages, import, confirmation, raccourcis
  jm/                          plugin + pont JourneyMap
  compat/ModMenuIntegration    bouton Configurer de Mod Menu
src/test/java/                 tests unitaires (rastérisation, JSON)
```
